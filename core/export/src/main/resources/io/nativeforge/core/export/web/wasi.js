// NativeForge web runtime, part 1: a WASI preview 1 (wasi_snapshot_preview1) host for one
// wasm32-wasip1 command module, with an in-memory file system. No DOM access here.
// zlib license (see LICENSE.runtime in the NativeForge sources).
var NFWasi = (function () {
  "use strict";
  var E = { SUCCESS: 0, BADF: 8, EXIST: 20, INVAL: 28, IO: 29, ISDIR: 31, NOENT: 44, NOSYS: 52,
    NOTDIR: 54, NOTEMPTY: 55, NOTSUP: 58, PERM: 63, SPIPE: 70, NOTCAPABLE: 76 };
  var FILETYPE = { CHAR: 2, DIR: 3, FILE: 4 };
  var O_CREAT = 1, O_DIRECTORY = 2, O_EXCL = 4, O_TRUNC = 8, FD_APPEND = 1;
  var ALL_RIGHTS = 0xFFFFFFFFFFFFFFFFn;
  var encoder = new TextEncoder();
  var decoder = new TextDecoder();

  function Exit(code) { this.code = code; this.message = "exit " + code; }
  function Stopped() { this.message = "stopped"; }

  function isPromise(value) { return value !== null && typeof value === "object" && typeof value.then === "function"; }
  // Calls next(value) now, or once value settles when it is a promise.
  function then(value, next) { return isPromise(value) ? value.then(next) : next(value); }

  // Paths are kept normalized, relative to the root, without leading slash ("" is the root).
  function normalize(path) {
    var out = [];
    var parts = path.split("/");
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (part === "" || part === ".") continue;
      if (part === "..") { if (out.length === 0) return null; out.pop(); } else out.push(part);
    }
    return out.join("/");
  }
  function parentOf(path) { var i = path.lastIndexOf("/"); return i < 0 ? "" : path.slice(0, i); }
  function join(dir, path) { return path.charAt(0) === "/" ? path : (dir ? dir + "/" + path : path); }

  function MemFS() { this.files = new Map(); this.dirs = new Set([""]); this.inode = 1; }
  MemFS.prototype.mkdirs = function (path) {
    while (path && !this.dirs.has(path)) { this.dirs.add(path); path = parentOf(path); }
  };
  MemFS.prototype.writeFile = function (path, data) {
    var p = normalize(path);
    if (!p) throw new Error("bad path " + path);
    this.mkdirs(parentOf(p));
    this.files.set(p, { data: data, size: data.length, ino: this.inode++, mtime: Date.now() });
  };
  MemFS.prototype.readFile = function (path) {
    var node = this.files.get(normalize(path));
    return node ? node.data.subarray(0, node.size) : null;
  };
  MemFS.prototype.children = function (dir) {
    var prefix = dir ? dir + "/" : "";
    var names = new Map();
    function consider(path, type) {
      if (path.length > prefix.length && path.lastIndexOf(prefix, 0) === 0 && path.indexOf("/", prefix.length) < 0) {
        names.set(path.slice(prefix.length), type);
      }
    }
    this.dirs.forEach(function (d) { consider(d, FILETYPE.DIR); });
    this.files.forEach(function (_, f) { consider(f, FILETYPE.FILE); });
    return Array.from(names.entries()).sort(function (a, b) { return a[0] < b[0] ? -1 : 1; });
  };
  function ensureCapacity(node, size) {
    if (size <= node.data.length) return;
    var grown = new Uint8Array(Math.max(size, node.data.length * 2, 256));
    grown.set(node.data.subarray(0, node.size));
    node.data = grown;
  }

  // options: args, env (array of "K=V"), fs (MemFS), write(fd, bytes) and read(), which
  // returns a Uint8Array, null at end of input, or a promise of either (async hosts only).
  function Wasi(options) {
    this.args = options.args || ["program"];
    this.env = options.env || [];
    this.fs = options.fs || new MemFS();
    this.write = options.write;
    this.read = options.read;
    this.memory = null;
    this.stdin = new Uint8Array(0);
    this.fds = new Map([
      [0, { type: "stdio" }], [1, { type: "stdio" }], [2, { type: "stdio" }],
      [3, { type: "dir", path: "", preopen: "/" }],
    ]);
    this.nextFd = 4;
    this.imports = this.createImports();
  }

  Wasi.prototype.view = function () { return new DataView(this.memory.buffer); };
  Wasi.prototype.bytes = function () { return new Uint8Array(this.memory.buffer); };
  Wasi.prototype.string = function (ptr, len) { return decoder.decode(this.bytes().slice(ptr, ptr + len)); };
  Wasi.prototype.path = function (dirFd, ptr, len) {
    var dir = this.fds.get(dirFd);
    if (!dir) return { error: E.BADF };
    if (dir.type !== "dir") return { error: E.NOTDIR };
    var p = normalize(join(dir.path, this.string(ptr, len)));
    return p === null ? { error: E.NOTCAPABLE } : { path: p };
  };
  Wasi.prototype.allocFd = function (entry) {
    while (this.fds.has(this.nextFd)) this.nextFd++;
    var fd = this.nextFd++;
    this.fds.set(fd, entry);
    return fd;
  };
  // Copies the iovec list at (iovs, count) into one array of {ptr, len}.
  Wasi.prototype.iovecs = function (iovs, count) {
    var view = this.view(), list = [];
    for (var i = 0; i < count; i++) list.push({ ptr: view.getUint32(iovs + i * 8, true), len: view.getUint32(iovs + i * 8 + 4, true) });
    return list;
  };
  Wasi.prototype.gather = function (iovs, count) {
    var list = this.iovecs(iovs, count), total = 0, bytes = this.bytes();
    list.forEach(function (v) { total += v.len; });
    var out = new Uint8Array(total), at = 0;
    list.forEach(function (v) { out.set(bytes.subarray(v.ptr, v.ptr + v.len), at); at += v.len; });
    return out;
  };
  Wasi.prototype.scatter = function (iovs, count, source) {
    var list = this.iovecs(iovs, count), bytes = this.bytes(), at = 0;
    for (var i = 0; i < list.length && at < source.length; i++) {
      var n = Math.min(list[i].len, source.length - at);
      bytes.set(source.subarray(at, at + n), list[i].ptr);
      at += n;
    }
    return at;
  };
  Wasi.prototype.capacity = function (iovs, count) {
    var total = 0;
    this.iovecs(iovs, count).forEach(function (v) { total += v.len; });
    return total;
  };
  Wasi.prototype.filestat = function (ptr, type, size, ino, mtime) {
    var view = this.view();
    for (var i = 0; i < 64; i += 8) view.setBigUint64(ptr + i, 0n, true);
    view.setBigUint64(ptr + 8, BigInt(ino), true);
    view.setUint8(ptr + 16, type);
    view.setBigUint64(ptr + 24, 1n, true);
    view.setBigUint64(ptr + 32, BigInt(size), true);
    var ns = BigInt(Math.floor(mtime)) * 1000000n;
    view.setBigUint64(ptr + 40, ns, true);
    view.setBigUint64(ptr + 48, ns, true);
    view.setBigUint64(ptr + 56, ns, true);
    return E.SUCCESS;
  };
  Wasi.prototype.statPath = function (ptr, path) {
    if (this.fs.dirs.has(path)) return this.filestat(ptr, FILETYPE.DIR, 0, 0, Date.now());
    var node = this.fs.files.get(path);
    return node ? this.filestat(ptr, FILETYPE.FILE, node.size, node.ino, node.mtime) : E.NOENT;
  };
  Wasi.prototype.readFileAt = function (node, pos, iovs, count) {
    var end = Math.min(node.size, pos + this.capacity(iovs, count));
    return pos >= end ? 0 : this.scatter(iovs, count, node.data.subarray(pos, end));
  };
  Wasi.prototype.writeFileAt = function (node, pos, data) {
    ensureCapacity(node, pos + data.length);
    if (pos > node.size) node.data.fill(0, node.size, pos);
    node.data.set(data, pos);
    node.size = Math.max(node.size, pos + data.length);
    node.mtime = Date.now();
  };
  Wasi.prototype.now = function (id) {
    if (id === 0) return BigInt(Math.floor((performance.timeOrigin + performance.now()) * 1e6));
    return BigInt(Math.floor(performance.now() * 1e6));
  };

  Wasi.prototype.createImports = function () {
    var self = this;
    function u32(ptr, value) { self.view().setUint32(ptr, value, true); return E.SUCCESS; }
    function u64(ptr, value) { self.view().setBigUint64(ptr, BigInt(value), true); return E.SUCCESS; }
    function strings(list) { return list.map(function (s) { return encoder.encode(s + "\0"); }); }
    function sizes(list, countPtr, sizePtr) {
      var encoded = strings(list), total = 0;
      encoded.forEach(function (b) { total += b.length; });
      u32(countPtr, encoded.length);
      return u32(sizePtr, total);
    }
    function fill(list, ptrs, buf) {
      var view = self.view(), bytes = self.bytes();
      strings(list).forEach(function (b, i) { view.setUint32(ptrs + i * 4, buf, true); bytes.set(b, buf); buf += b.length; });
      return E.SUCCESS;
    }
    function file(fd) {
      var entry = self.fds.get(fd);
      if (!entry) return { error: E.BADF };
      if (entry.type === "dir") return { error: E.ISDIR };
      if (entry.type !== "file") return { error: E.SPIPE };
      var node = self.fs.files.get(entry.path);
      return node ? { entry: entry, node: node } : { error: E.BADF };
    }
    function readStdin(iovs, count, out) {
      if (self.stdin.length === 0) {
        return then(self.read(), function (chunk) {
          if (chunk === null || chunk === undefined) return u32(out, 0); // end of input
          self.stdin = chunk;
          return readStdin(iovs, count, out);
        });
      }
      var n = self.scatter(iovs, count, self.stdin);
      self.stdin = self.stdin.subarray(n);
      return u32(out, n);
    }

    var wasi = {
      args_sizes_get: function (c, s) { return sizes(self.args, c, s); },
      args_get: function (p, b) { return fill(self.args, p, b); },
      environ_sizes_get: function (c, s) { return sizes(self.env, c, s); },
      environ_get: function (p, b) { return fill(self.env, p, b); },
      clock_res_get: function (id, out) { return u64(out, 1000n); },
      clock_time_get: function (id, precision, out) { return id > 3 ? E.INVAL : u64(out, self.now(id)); },
      random_get: function (buf, len) {
        for (var at = 0; at < len; at += 65536) crypto.getRandomValues(self.bytes().subarray(buf + at, buf + Math.min(len, at + 65536)));
        return E.SUCCESS;
      },
      proc_exit: function (code) { throw new Exit(code); },
      proc_raise: function (signal) { throw new Exit(128 + signal); },
      sched_yield: function () { return E.SUCCESS; },

      fd_write: function (fd, iovs, count, out) {
        var entry = self.fds.get(fd);
        if (!entry) return E.BADF;
        var data = self.gather(iovs, count);
        if (entry.type === "stdio") {
          if (fd === 0) return E.BADF;
          return then(self.write(fd, data), function () { return u32(out, data.length); });
        }
        var f = file(fd);
        if (f.error) return f.error;
        var pos = f.entry.append ? f.node.size : f.entry.pos;
        self.writeFileAt(f.node, pos, data);
        f.entry.pos = pos + data.length;
        return u32(out, data.length);
      },
      fd_pwrite: function (fd, iovs, count, offset, out) {
        var f = file(fd);
        if (f.error) return f.error;
        var data = self.gather(iovs, count);
        self.writeFileAt(f.node, Number(offset), data);
        return u32(out, data.length);
      },
      fd_read: function (fd, iovs, count, out) {
        var entry = self.fds.get(fd);
        if (!entry) return E.BADF;
        if (entry.type === "stdio") return fd === 0 ? readStdin(iovs, count, out) : E.BADF;
        var f = file(fd);
        if (f.error) return f.error;
        var n = self.readFileAt(f.node, f.entry.pos, iovs, count);
        f.entry.pos += n;
        return u32(out, n);
      },
      fd_pread: function (fd, iovs, count, offset, out) {
        var f = file(fd);
        if (f.error) return f.error;
        return u32(out, self.readFileAt(f.node, Number(offset), iovs, count));
      },
      fd_seek: function (fd, offset, whence, out) {
        var f = file(fd);
        if (f.error) return f.error;
        var base = whence === 0 ? 0 : whence === 1 ? f.entry.pos : whence === 2 ? f.node.size : -1;
        if (base < 0) return E.INVAL;
        var pos = base + Number(offset);
        if (pos < 0) return E.INVAL;
        f.entry.pos = pos;
        return u64(out, pos);
      },
      fd_tell: function (fd, out) {
        var f = file(fd);
        return f.error ? f.error : u64(out, f.entry.pos);
      },
      fd_close: function (fd) { return self.fds.delete(fd) ? E.SUCCESS : E.BADF; },
      fd_renumber: function (from, to) {
        var entry = self.fds.get(from);
        if (!entry || !self.fds.has(to)) return E.BADF;
        self.fds.set(to, entry);
        self.fds.delete(from);
        return E.SUCCESS;
      },
      fd_sync: function (fd) { return self.fds.has(fd) ? E.SUCCESS : E.BADF; },
      fd_datasync: function (fd) { return self.fds.has(fd) ? E.SUCCESS : E.BADF; },
      fd_advise: function (fd) { return self.fds.has(fd) ? E.SUCCESS : E.BADF; },
      fd_allocate: function (fd, offset, len) {
        var f = file(fd);
        if (f.error) return f.error;
        var end = Number(offset) + Number(len);
        if (end > f.node.size) self.writeFileAt(f.node, f.node.size, new Uint8Array(end - f.node.size));
        return E.SUCCESS;
      },
      fd_fdstat_get: function (fd, out) {
        var entry = self.fds.get(fd);
        if (!entry) return E.BADF;
        var view = self.view();
        view.setUint8(out, entry.type === "stdio" ? FILETYPE.CHAR : entry.type === "dir" ? FILETYPE.DIR : FILETYPE.FILE);
        view.setUint16(out + 2, entry.append ? FD_APPEND : 0, true);
        view.setBigUint64(out + 8, ALL_RIGHTS, true);
        view.setBigUint64(out + 16, ALL_RIGHTS, true);
        return E.SUCCESS;
      },
      fd_fdstat_set_flags: function (fd, flags) {
        var entry = self.fds.get(fd);
        if (!entry) return E.BADF;
        entry.append = (flags & FD_APPEND) !== 0;
        return E.SUCCESS;
      },
      fd_fdstat_set_rights: function (fd) { return self.fds.has(fd) ? E.SUCCESS : E.BADF; },
      fd_filestat_get: function (fd, out) {
        var entry = self.fds.get(fd);
        if (!entry) return E.BADF;
        if (entry.type === "stdio") return self.filestat(out, FILETYPE.CHAR, 0, 0, Date.now());
        return self.statPath(out, entry.path);
      },
      fd_filestat_set_size: function (fd, size) {
        var f = file(fd);
        if (f.error) return f.error;
        var n = Number(size);
        if (n > f.node.size) self.writeFileAt(f.node, f.node.size, new Uint8Array(n - f.node.size));
        else f.node.size = n;
        return E.SUCCESS;
      },
      fd_filestat_set_times: function (fd) { return self.fds.has(fd) ? E.SUCCESS : E.BADF; },
      fd_prestat_get: function (fd, out) {
        var entry = self.fds.get(fd);
        if (!entry || !entry.preopen) return E.BADF;
        self.view().setUint8(out, 0);
        return u32(out + 4, encoder.encode(entry.preopen).length);
      },
      fd_prestat_dir_name: function (fd, ptr, len) {
        var entry = self.fds.get(fd);
        if (!entry || !entry.preopen) return E.BADF;
        self.bytes().set(encoder.encode(entry.preopen).subarray(0, len), ptr);
        return E.SUCCESS;
      },
      fd_readdir: function (fd, buf, len, cookie, out) {
        var entry = self.fds.get(fd);
        if (!entry) return E.BADF;
        if (entry.type !== "dir") return E.NOTDIR;
        var list = [[".", FILETYPE.DIR], ["..", FILETYPE.DIR]].concat(self.fs.children(entry.path));
        var chunks = [], total = 0;
        for (var i = Number(cookie); i < list.length && total < len; i++) {
          var name = encoder.encode(list[i][0]);
          var dirent = new Uint8Array(24 + name.length);
          var view = new DataView(dirent.buffer);
          view.setBigUint64(0, BigInt(i + 1), true);
          view.setBigUint64(8, BigInt(i + 1), true);
          view.setUint32(16, name.length, true);
          view.setUint8(20, list[i][1]);
          dirent.set(name, 24);
          chunks.push(dirent);
          total += dirent.length;
        }
        var bytes = self.bytes(), at = 0;
        chunks.forEach(function (c) {
          var n = Math.min(c.length, len - at);
          if (n > 0) bytes.set(c.subarray(0, n), buf + at);
          at += n;
        });
        return u32(out, at);
      },

      path_open: function (dirFd, dirFlags, ptr, len, oflags, rightsBase, rightsInheriting, fdflags, out) {
        var r = self.path(dirFd, ptr, len);
        if (r.error) return r.error;
        var p = r.path, fs = self.fs;
        if (fs.dirs.has(p)) {
          if ((oflags & O_CREAT) && (oflags & O_EXCL)) return E.EXIST;
          if (oflags & O_TRUNC) return E.ISDIR;
          return u32(out, self.allocFd({ type: "dir", path: p }));
        }
        var node = fs.files.get(p);
        if (oflags & O_DIRECTORY) return node ? E.NOTDIR : E.NOENT;
        if (node) {
          if ((oflags & O_CREAT) && (oflags & O_EXCL)) return E.EXIST;
          if (oflags & O_TRUNC) node.size = 0;
        } else {
          if (!(oflags & O_CREAT)) return E.NOENT;
          if (!fs.dirs.has(parentOf(p))) return fs.files.has(parentOf(p)) ? E.NOTDIR : E.NOENT;
          fs.writeFile(p, new Uint8Array(0));
        }
        return u32(out, self.allocFd({ type: "file", path: p, pos: 0, append: (fdflags & FD_APPEND) !== 0 }));
      },
      path_filestat_get: function (dirFd, flags, ptr, len, out) {
        var r = self.path(dirFd, ptr, len);
        return r.error ? r.error : self.statPath(out, r.path);
      },
      path_filestat_set_times: function (dirFd, flags, ptr, len) {
        var r = self.path(dirFd, ptr, len);
        if (r.error) return r.error;
        return self.fs.dirs.has(r.path) || self.fs.files.has(r.path) ? E.SUCCESS : E.NOENT;
      },
      path_create_directory: function (dirFd, ptr, len) {
        var r = self.path(dirFd, ptr, len);
        if (r.error) return r.error;
        if (self.fs.dirs.has(r.path) || self.fs.files.has(r.path)) return E.EXIST;
        if (!self.fs.dirs.has(parentOf(r.path))) return E.NOENT;
        self.fs.dirs.add(r.path);
        return E.SUCCESS;
      },
      path_remove_directory: function (dirFd, ptr, len) {
        var r = self.path(dirFd, ptr, len);
        if (r.error) return r.error;
        if (r.path === "") return E.PERM;
        if (!self.fs.dirs.has(r.path)) return self.fs.files.has(r.path) ? E.NOTDIR : E.NOENT;
        if (self.fs.children(r.path).length > 0) return E.NOTEMPTY;
        self.fs.dirs.delete(r.path);
        return E.SUCCESS;
      },
      path_unlink_file: function (dirFd, ptr, len) {
        var r = self.path(dirFd, ptr, len);
        if (r.error) return r.error;
        if (self.fs.dirs.has(r.path)) return E.ISDIR;
        return self.fs.files.delete(r.path) ? E.SUCCESS : E.NOENT;
      },
      path_rename: function (oldFd, oldPtr, oldLen, newFd, newPtr, newLen) {
        var from = self.path(oldFd, oldPtr, oldLen), to = self.path(newFd, newPtr, newLen);
        if (from.error) return from.error;
        if (to.error) return to.error;
        var fs = self.fs;
        if (!fs.dirs.has(parentOf(to.path))) return E.NOENT;
        var node = fs.files.get(from.path);
        if (node) {
          if (fs.dirs.has(to.path)) return E.ISDIR;
          fs.files.delete(from.path);
          fs.files.set(to.path, node);
          return E.SUCCESS;
        }
        if (!fs.dirs.has(from.path) || from.path === "") return E.NOENT;
        if (fs.files.has(to.path)) return E.NOTDIR;
        if ((to.path + "/").lastIndexOf(from.path + "/", 0) === 0) return E.INVAL;
        var prefix = from.path + "/";
        Array.from(fs.dirs).forEach(function (d) {
          if (d === from.path || d.lastIndexOf(prefix, 0) === 0) { fs.dirs.delete(d); fs.dirs.add(to.path + d.slice(from.path.length)); }
        });
        Array.from(fs.files.keys()).forEach(function (f) {
          if (f.lastIndexOf(prefix, 0) === 0) { var n = fs.files.get(f); fs.files.delete(f); fs.files.set(to.path + f.slice(from.path.length), n); }
        });
        return E.SUCCESS;
      },
      path_readlink: function () { return E.INVAL; },
      path_symlink: function () { return E.NOTSUP; },
      path_link: function () { return E.NOTSUP; },

      poll_oneoff: function (inPtr, outPtr, count, eventsPtr) {
        var view = self.view(), clocks = [], ready = [], wait = Infinity;
        for (var i = 0; i < count; i++) {
          var s = inPtr + i * 48;
          var sub = { userdata: view.getBigUint64(s, true), tag: view.getUint8(s + 8) };
          if (sub.tag === 0) {
            var id = view.getUint32(s + 16, true);
            var timeout = view.getBigUint64(s + 24, true);
            var absolute = (view.getUint16(s + 40, true) & 1) !== 0;
            var ns = absolute ? Number(timeout - self.now(id)) : Number(timeout);
            sub.ms = Math.max(0, ns / 1e6);
            wait = Math.min(wait, sub.ms);
            clocks.push(sub);
          } else {
            ready.push(sub); // stdin and files never block here; a read may wait instead
          }
        }
        function report() {
          var events = ready.length ? ready : clocks.filter(function (c) { return c.ms <= wait; });
          var v = self.view();
          events.forEach(function (sub, i) {
            var e = outPtr + i * 32;
            for (var k = 0; k < 32; k += 8) v.setBigUint64(e + k, 0n, true);
            v.setBigUint64(e, sub.userdata, true);
            v.setUint8(e + 10, sub.tag);
            if (sub.tag !== 0) v.setBigUint64(e + 16, 1n, true);
          });
          return u32(eventsPtr, events.length);
        }
        if (ready.length || wait === Infinity || wait <= 0) return report();
        return then(self.sleep(wait), report);
      },
    };

    // Unknown functions (sockets, newer additions) fail with ENOSYS instead of a link error.
    return {
      wasi_snapshot_preview1: new Proxy(wasi, {
        get: function (target, name) { return name in target ? target[name] : function () { return E.NOSYS; }; },
      }),
    };
  };

  // Overridden by asynchronous hosts; this one can only spin.
  Wasi.prototype.sleep = function (ms) {
    var end = performance.now() + ms;
    while (performance.now() < end) { /* busy wait: synchronous hosts cannot yield */ }
  };

  // Returns the exit code. Asynchronous hosts pass `suspending` (JSPI), so that read, write
  // and poll_oneoff may return promises and the page keeps drawing while the program runs.
  Wasi.prototype.start = function (module, suspending) {
    var self = this;
    var imports = this.imports;
    if (suspending) {
      var wasi = this.imports.wasi_snapshot_preview1;
      var wrapped = new Proxy({}, {
        get: function (_, name) {
          var fn = wasi[name];
          return ["fd_read", "fd_write", "poll_oneoff"].indexOf(name) >= 0 ? new WebAssembly.Suspending(fn) : fn;
        },
      });
      imports = { wasi_snapshot_preview1: wrapped };
    }
    return WebAssembly.instantiate(module, imports).then(function (instance) {
      self.memory = instance.exports.memory;
      var main = instance.exports._start;
      if (typeof main !== "function") throw new Error("The module has no _start function (not a WASI command)");
      if (suspending) main = WebAssembly.promising(main);
      var finished = function () { return 0; };
      var failed = function (error) { if (error instanceof Exit) return error.code; throw error; };
      try {
        var result = main();
        return isPromise(result) ? result.then(finished, failed) : finished();
      } catch (error) {
        return failed(error);
      }
    });
  };

  return { Wasi: Wasi, MemFS: MemFS, Exit: Exit, Stopped: Stopped, errno: E,
    supportsSuspending: function () { return typeof WebAssembly.Suspending === "function" && typeof WebAssembly.promising === "function"; } };
})();
if (typeof module !== "undefined") module.exports = NFWasi;
