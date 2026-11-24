// NativeForge web runtime, part 2: the console page (terminal, input, run and stop).
// zlib license (see LICENSE.runtime in the NativeForge sources).
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var config = JSON.parse($("nf-config").textContent);
  var screenEl = $("screen"), main = $("terminal"), form = $("prompt"), input = $("stdin");
  var statusEl = $("status"), runButton = $("run"), stopButton = $("stop");
  var encoder = new TextEncoder();
  var suspending = NFWasi.supportsSuspending();
  var MAX_LINES = 5000;

  function decodeBase64(text) {
    text = text.replace(/\s+/g, "");
    if (typeof Uint8Array.fromBase64 === "function") return Uint8Array.fromBase64(text);
    var binary = atob(text), out = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  }

  // ---- Terminal: lines of spans, with the ANSI codes console programs commonly print. ----
  var PALETTE = ["#3f4451", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd", "#56b6c2", "#d7dae0",
    "#7f848e", "#ef7b84", "#a5d68a", "#f0cf8f", "#74baf5", "#d68ce9", "#67c5d1", "#ffffff"];
  function color256(n) {
    if (n < 16) return PALETTE[n];
    if (n >= 232) { var g = 8 + (n - 232) * 10; return "rgb(" + g + "," + g + "," + g + ")"; }
    n -= 16;
    var level = function (v) { return v === 0 ? 0 : 55 + v * 40; };
    return "rgb(" + level(Math.floor(n / 36)) + "," + level(Math.floor(n / 6) % 6) + "," + level(n % 6) + ")";
  }

  var term = {
    line: null, text: "", style: {}, state: 0, seq: "", cr: false, homed: false, kind: "",
    reset: function () {
      screenEl.textContent = "";
      this.style = {}; this.state = 0; this.text = ""; this.cr = false; this.homed = false;
      this.newLine();
    },
    newLine: function () {
      this.flush();
      this.line = document.createElement("div");
      this.line.className = "line";
      screenEl.appendChild(this.line);
      while (screenEl.childNodes.length > MAX_LINES) screenEl.removeChild(screenEl.firstChild);
    },
    flush: function () {
      if (!this.text) return;
      var s = this.style, span = document.createElement("span");
      span.textContent = this.text;
      this.text = "";
      if (this.kind) span.className = this.kind;
      var fg = s.fg, bg = s.bg;
      if (s.inverse) { fg = s.bg || "var(--bg)"; bg = s.fg || "var(--fg)"; }
      if (fg) span.style.color = fg;
      if (bg) span.style.background = bg;
      if (s.bold) span.style.fontWeight = "bold";
      if (s.dim) span.style.opacity = "0.7";
      if (s.italic) span.style.fontStyle = "italic";
      if (s.underline) span.style.textDecoration = "underline";
      this.line.appendChild(span);
    },
    clearLine: function () { this.text = ""; this.line.textContent = ""; },
    backspace: function () {
      if (this.text) { this.text = this.text.slice(0, -1); return; }
      var last = this.line.lastChild;
      if (!last) return;
      if (last.textContent.length > 1) last.textContent = last.textContent.slice(0, -1);
      else this.line.removeChild(last);
    },
    sgr: function (params) {
      this.flush();
      var p = params === "" ? [0] : params.split(";").map(function (x) { return parseInt(x, 10) || 0; });
      var s = this.style;
      for (var i = 0; i < p.length; i++) {
        var c = p[i];
        if (c === 0) this.style = s = {};
        else if (c === 1) s.bold = true;
        else if (c === 2) s.dim = true;
        else if (c === 3) s.italic = true;
        else if (c === 4) s.underline = true;
        else if (c === 7) s.inverse = true;
        else if (c === 22) s.bold = s.dim = false;
        else if (c === 23) s.italic = false;
        else if (c === 24) s.underline = false;
        else if (c === 27) s.inverse = false;
        else if (c >= 30 && c <= 37) s.fg = PALETTE[c - 30];
        else if (c >= 90 && c <= 97) s.fg = PALETTE[c - 82];
        else if (c >= 40 && c <= 47) s.bg = PALETTE[c - 40];
        else if (c >= 100 && c <= 107) s.bg = PALETTE[c - 92];
        else if (c === 39) s.fg = null;
        else if (c === 49) s.bg = null;
        else if ((c === 38 || c === 48) && p[i + 1] === 5) { s[c === 38 ? "fg" : "bg"] = color256(p[i + 2] & 255); i += 2; }
        else if ((c === 38 || c === 48) && p[i + 1] === 2) {
          s[c === 38 ? "fg" : "bg"] = "rgb(" + (p[i + 2] & 255) + "," + (p[i + 3] & 255) + "," + (p[i + 4] & 255) + ")";
          i += 4;
        }
      }
    },
    csi: function (seq) {
      var final = seq.charAt(seq.length - 1), params = seq.slice(0, -1);
      if (final === "m") return this.sgr(params);
      if (final === "H" || final === "f") { this.homed = params === "" || params === "1;1" || params === "0;0"; return; }
      if (final === "J" && (params === "2" || params === "3" || this.homed)) { this.reset(); return; }
      if (final === "K") { this.flush(); if (params === "2") this.clearLine(); }
    },
    write: function (chunk, kind) {
      if (kind !== this.kind) { this.flush(); this.kind = kind; }
      for (var i = 0; i < chunk.length; i++) {
        var ch = chunk.charAt(i);
        if (this.state === 1) { this.state = ch === "[" ? 2 : ch === "]" ? 3 : 0; this.seq = ""; continue; }
        if (this.state === 2) {
          this.seq += ch;
          var code = ch.charCodeAt(0);
          if (code >= 0x40 && code <= 0x7e) { this.state = 0; this.csi(this.seq); }
          else if (this.seq.length > 32) this.state = 0;
          continue;
        }
        if (this.state === 3) { if (ch === "\x07") this.state = 0; else if (ch === "\x1b") this.state = 4; continue; }
        if (this.state === 4) { this.state = 0; continue; }
        if (ch === "\x1b") { this.state = 1; continue; }
        if (ch === "\n") { this.cr = false; this.newLine(); continue; }
        if (ch === "\r") { this.cr = true; continue; }
        if (ch === "\b") { this.backspace(); continue; }
        if (ch < " " && ch !== "\t") continue;
        if (this.cr) { this.cr = false; this.clearLine(); }
        this.homed = false;
        this.text += ch;
      }
      this.flush();
      main.scrollTop = main.scrollHeight;
    },
    lastLine: function () { return this.line ? this.line.textContent : ""; },
  };

  // ---- Running the program. ----
  var compiled = null;
  var run = null; // the current run: { stop, queue, waiting, sleeping }

  function setStatus(text, kind) { statusEl.textContent = text; statusEl.className = "pill " + (kind || ""); }

  function describe(error) {
    var message = String(error && error.message || error);
    if (error instanceof WebAssembly.RuntimeError && /unreachable/.test(message)) return "The program aborted (abort(), a failed assert or a trap).";
    if (error instanceof RangeError && /stack/i.test(message)) return "Stack overflow: too much recursion.";
    if (error instanceof WebAssembly.RuntimeError) return "The program crashed: " + message;
    return message;
  }

  function loadModule() {
    if (!compiled) compiled = WebAssembly.compile(decodeBase64($("nf-wasm").textContent));
    return compiled;
  }

  function deliver(current, value) {
    if (current.waiting) { var resolve = current.waiting; current.waiting = null; resolve(value); }
    else current.queue.push(value);
  }

  function start() {
    if (run) return;
    var current = run = { stopped: false, queue: [], waiting: null, sleeping: null, lastYield: performance.now() };
    term.reset();
    setStatus("Running…", "running");
    runButton.disabled = true;
    stopButton.hidden = !suspending;
    form.hidden = !suspending;
    input.value = "";

    var check = function () { if (current.stopped) throw new NFWasi.Stopped(); };
    var fs = new NFWasi.MemFS();
    (config.files || []).forEach(function (f) { fs.writeFile(f.path, decodeBase64(f.data)); });
    var decoders = { 1: new TextDecoder(), 2: new TextDecoder() };
    var wasi = new NFWasi.Wasi({
      args: [config.program].concat(config.args || []),
      env: config.env || [],
      fs: fs,
      write: function (fd, bytes) {
        term.write(decoders[fd].decode(bytes, { stream: true }), fd === 2 ? "err" : "");
        if (!suspending) return undefined;
        check();
        // Let the browser draw now and then; each yield costs a stack switch.
        if (performance.now() - current.lastYield < 30) return undefined;
        return new Promise(function (resolve) { setTimeout(resolve, 0); }).then(function () {
          current.lastYield = performance.now();
          check();
        });
      },
      read: suspending ? function () {
        check();
        if (current.queue.length) return current.queue.shift();
        if (document.activeElement !== input) input.focus({ preventScroll: true });
        return new Promise(function (resolve) { current.waiting = resolve; }).then(function (value) { check(); return value; });
      } : function () {
        if (current.queue.length) return current.queue.shift();
        var answer = window.prompt(term.lastLine() || "Input for " + config.title, "");
        if (answer === null) return null;
        term.write(answer + "\n", "in");
        return encoder.encode(answer + "\n");
      },
    });
    if (suspending) {
      wasi.sleep = function (ms) {
        return new Promise(function (resolve) {
          current.sleeping = resolve;
          setTimeout(resolve, ms);
        }).then(function () { current.sleeping = null; current.lastYield = performance.now(); check(); });
      };
    }

    var finish = function (text, kind, error) {
      [1, 2].forEach(function (fd) { var rest = decoders[fd].decode(); if (rest) term.write(rest, fd === 2 ? "err" : ""); });
      if (error) term.write((term.lastLine() ? "\n" : "") + error + "\n", "err");
      setStatus(text, kind);
      run = null;
      runButton.disabled = false;
      stopButton.hidden = true;
      form.hidden = true;
    };
    // Synchronous browsers block while the program runs: show "Running…" first.
    setTimeout(function () {
      loadModule()
        .then(function (m) { return wasi.start(m, suspending); })
        .then(function (code) {
          finish(code === 0 ? "Finished" : "Exited with code " + code, code === 0 ? "ok" : "warn");
        }, function (error) {
          if (error instanceof NFWasi.Stopped) finish("Stopped", "warn");
          else finish("Crashed", "error", describe(error));
        });
    }, suspending ? 0 : 30);
  }

  function stop() {
    var current = run;
    if (!current) return;
    current.stopped = true;
    if (current.sleeping) current.sleeping();
    deliver(current, null);
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    if (!run) return;
    var text = input.value;
    input.value = "";
    term.write(text + "\n", "in");
    deliver(run, encoder.encode(text + "\n"));
  });
  input.addEventListener("keydown", function (event) {
    if (event.key === "d" && event.ctrlKey && run) { event.preventDefault(); deliver(run, null); }
  });
  $("eof").addEventListener("click", function () { if (run) { deliver(run, null); input.focus(); } });
  runButton.addEventListener("click", start);
  stopButton.addEventListener("click", stop);
  main.addEventListener("click", function () { if (run && suspending && !window.getSelection().toString()) input.focus({ preventScroll: true }); });
  $("hint").textContent = suspending ? "" : "Input is asked in a dialog in this browser.";

  if (typeof WebAssembly !== "object") {
    setStatus("Unsupported", "error");
    term.reset();
    term.write("This browser does not support WebAssembly.\n", "err");
  } else {
    start();
  }
})();
