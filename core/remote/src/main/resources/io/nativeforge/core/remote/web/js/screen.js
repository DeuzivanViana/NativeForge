// The picture of a graphical program running on the phone, decoded with WebCodecs, plus the
// keyboard, mouse and touch input that goes back to it (pointer and wheel as small binary
// messages, the rest as JSON). See ScreenHub.kt for the wire format.
import { wsUrl } from './api.js';
import { h, fill, icon, iconButton } from './util.js';

const MOD_SHIFT = 1, MOD_CTRL = 2, MOD_ALT = 4, MOD_META = 8, MOD_CAPS = 16;
// Binary input messages (ScreenHub.Viewer.receive): pointer and wheel, the frequent ones.
const BIN_POINTER = 1, BIN_WHEEL = 2;
const POINTER_ACTIONS = { down: 0, move: 1, up: 2, cancel: 3, hover: 4 };

export const canDecode = () => typeof window.VideoDecoder === 'function' && window.isSecureContext;

export class ScreenView {
  /** @param session { id, title } @param onStop asks the phone to end the program @param onClose hides the view */
  constructor(session, { onStop, onClose }) {
    this.id = session.id;
    this.onStop = onStop;
    this.onClose = onClose;
    this.width = 0;
    this.height = 0;
    this.codec = null;
    this.decoder = null;
    this.needKey = true;
    this.ended = false;
    this.disposed = false;
    this.touchMode = false;
    this.touchIds = new Map(); // browser pointerId -> small id
    this.frames = 0;
    this.bytes = 0;
    this.pendingMove = null;
    this.fixed = false; // the program chose its window size, so the panel's size doesn't change it
    this.sentSize = '';
    this.maxFps = 60; // the phone lowers it on a slow network, a slow browser or when it is hot
    this.hot = false;
    this.lastLag = 0;

    this.canvas = h('canvas', { class: 'screen-canvas', tabIndex: 0, title: 'Click to send keyboard and mouse to the program' });
    this.message = h('div', { class: 'screen-message' }, 'Connecting…');
    this.stats = h('span', { class: 'sub' }, '');
    this.modeBtn = iconButton('mouse', 'Mouse acts as a mouse (click to act as touch)', () => this.toggleMode());
    this.stage = h('div', { class: 'screen-stage' }, this.canvas, this.message);
    this.el = h('div', { class: 'screen-pane' },
      h('div', { class: 'screen-bar' },
        icon('game', 'i s'), h('span', { class: 'title' }, session.title), this.stats, h('div', { class: 'spacer' }),
        this.modeBtn,
        iconButton('fullscreen', 'Full screen (keys like Esc go to the program)', () => this.fullscreen()),
        iconButton('stop', 'Stop the program', () => this.onStop(), 'danger'),
        iconButton('close', 'Hide (the program keeps running)', () => this.onClose())),
      this.stage);
    this.ctx = this.canvas.getContext('2d', { alpha: false, desynchronized: true });
    this.bindInput();
    this.statsTimer = setInterval(() => this.updateStats(), 1000);
    // The program's display follows this panel's size (in device pixels, like the first run).
    this.resizeTimer = null;
    this.resizeObserver = new ResizeObserver(() => {
      clearTimeout(this.resizeTimer);
      this.resizeTimer = setTimeout(() => this.sendPanelSize(), 250);
    });
    this.resizeObserver.observe(this.stage);
    if (!canDecode()) {
      this.showMessage('This browser cannot decode the video. Use a recent Chrome, Edge, Firefox (130 or newer) or Safari (16.4 or newer).');
      return;
    }
    this.connect();
  }

  connect() {
    const ws = new WebSocket(wsUrl(`/ws/screen?id=${encodeURIComponent(this.id)}`));
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    ws.onmessage = (e) => (typeof e.data === 'string' ? this.onText(e.data) : this.onVideo(e.data));
    ws.onclose = (e) => {
      if (this.disposed || this.ended) return;
      if (e.code === 4004) this.end('The program is not running any more');
      else if (e.code === 4001) this.end('Signed out');
      else setTimeout(() => !this.disposed && !this.ended && this.connect(), 1000);
    };
  }

  onText(text) {
    let m;
    try { m = JSON.parse(text); } catch { return; }
    if (m.t === 'hello' || m.t === 'config' || m.t === 'size') {
      if (typeof m.fixed === 'boolean') this.fixed = m.fixed;
      const resized = m.width && m.height && (m.width !== this.width || m.height !== this.height);
      if (m.width && m.height) this.resize(m.width, m.height);
      if (m.codec && m.codec !== this.codec) this.configure(m.codec);
      // A new size comes from a new encoder: start again from its next key frame.
      else if (resized && m.t !== 'hello') this.resetDecoder();
      if (m.t === 'hello') { this.sentSize = ''; this.sendPanelSize(); }
    } else if (m.t === 'quality') {
      this.maxFps = m.fps || 60;
      this.hot = !!m.hot;
    } else if (m.t === 'end') {
      this.end(m.reason);
    }
  }

  resize(width, height) {
    if (width === this.width && height === this.height) return;
    this.width = width;
    this.height = height;
    this.canvas.width = width;
    this.canvas.height = height;
  }

  configure(codec) {
    this.codec = codec;
    try { this.decoder?.close(); } catch { /* already closed */ }
    this.decoder = null;
    const config = { codec, optimizeForLatency: true };
    VideoDecoder.isConfigSupported(config).then(({ supported }) => {
      if (!supported) { this.showMessage(`This browser cannot decode ${codec} video.`); return; }
      this.decoder = new VideoDecoder({
        output: (frame) => this.draw(frame),
        error: (e) => { console.warn('decoder', e); this.resetDecoder(); },
      });
      this.decoder.configure(config);
      this.needKey = true;
      this.requestKeyFrame();
    }).catch((e) => this.showMessage(`Video error: ${e.message}`));
  }

  resetDecoder() {
    if (this.disposed || !this.codec) return;
    const codec = this.codec;
    this.codec = null;
    this.configure(codec);
  }

  onVideo(buffer) {
    const bytes = new Uint8Array(buffer);
    if (bytes.length < 10 || !this.decoder || this.decoder.state !== 'configured') return;
    const key = (bytes[0] & 1) !== 0;
    if (this.needKey && !key) return;
    // A decoder that falls behind adds latency: drop until the next key frame.
    if (this.decoder.decodeQueueSize > 8 && !key) { this.needKey = true; this.requestKeyFrame(); this.lag(); return; }
    const view = new DataView(buffer);
    const timestamp = view.getUint32(1) * 2 ** 32 + view.getUint32(5);
    try {
      this.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp, data: bytes.subarray(9) }));
      this.needKey = false;
      this.bytes += bytes.length;
    } catch (e) {
      console.warn('decode', e);
      this.needKey = true;
      this.requestKeyFrame();
    }
  }

  draw(frame) {
    if (this.disposed) { frame.close(); return; }
    if (frame.displayWidth !== this.width || frame.displayHeight !== this.height) this.resize(frame.displayWidth, frame.displayHeight);
    this.ctx.drawImage(frame, 0, 0, this.width, this.height);
    frame.close();
    this.frames++;
    if (this.message.style.display !== 'none') this.message.style.display = 'none';
  }

  sendPanelSize() {
    if (this.ended || this.disposed || this.ws?.readyState !== WebSocket.OPEN) return;
    const rect = this.stage.getBoundingClientRect();
    if (rect.width < 16 || rect.height < 16) return; // hidden
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = Math.round(rect.width * dpr), hgt = Math.round(rect.height * dpr);
    const key = `${w}x${hgt}`;
    if (key === this.sentSize) return;
    this.sentSize = key;
    this.send({ t: 'resize', w, h: hgt });
  }

  updateStats() {
    if (this.ended) return;
    const size = `${this.width}×${this.height}${this.fixed ? ' (set by the program)' : ''}`;
    const cap = this.hot ? ` · ${this.maxFps} fps max (phone is hot)` : this.maxFps < 60 ? ` · ${this.maxFps} fps max` : '';
    this.stats.textContent = this.frames ? `${size} · ${this.frames} fps · ${Math.round(this.bytes * 8 / 1000)} kbit/s${cap}` : '';
    this.frames = 0;
    this.bytes = 0;
  }

  showMessage(text) {
    this.message.textContent = text;
    this.message.style.display = '';
  }

  end(reason) {
    if (this.ended) return;
    this.ended = true;
    this.releaseKeys();
    this.stats.textContent = '';
    this.showMessage(reason ? `Program ended: ${reason}` : 'Program ended');
    this.message.append(h('div', null, h('button', { class: 'btn', onclick: () => this.onClose() }, 'Close')));
    try { this.ws?.close(1000); } catch { /* closed */ }
  }

  send(message) { if (this.ws?.readyState === WebSocket.OPEN && !this.ended) this.ws.send(JSON.stringify(message)); }

  requestKeyFrame() { this.send({ t: 'keyframe' }); }

  /** Tells the phone this browser could not keep up (it lowers the frame and bit rate); at most once a second. */
  lag() {
    const now = performance.now();
    if (now - this.lastLag < 1000) return;
    this.lastLag = now;
    this.send({ t: 'lag' });
  }

  sendBinary(buffer) { if (this.ws?.readyState === WebSocket.OPEN && !this.ended) this.ws.send(buffer); }

  /** Pointer event as 13 bytes: [1][action][flags: 1 = touch][id][buttons][x f32][y f32]. */
  sendPointer({ a, x, y, b, id, k }) {
    const view = new DataView(new ArrayBuffer(13));
    view.setUint8(0, BIN_POINTER);
    view.setUint8(1, POINTER_ACTIONS[a]);
    view.setUint8(2, k === 'touch' ? 1 : 0);
    view.setUint8(3, id || 0);
    view.setUint8(4, b & 0xff);
    view.setFloat32(5, x);
    view.setFloat32(9, y);
    this.sendBinary(view.buffer);
  }

  /** Wheel event as 17 bytes: [2][x f32][y f32][dx f32][dy f32]. */
  sendWheel(x, y, dx, dy) {
    const view = new DataView(new ArrayBuffer(17));
    view.setUint8(0, BIN_WHEEL);
    view.setFloat32(1, x);
    view.setFloat32(5, y);
    view.setFloat32(9, dx);
    view.setFloat32(13, dy);
    this.sendBinary(view.buffer);
  }

  releaseKeys() { this.send({ t: 'release' }); }

  toggleMode() {
    this.touchMode = !this.touchMode;
    fill(this.modeBtn, icon(this.touchMode ? 'touch' : 'mouse'));
    this.modeBtn.title = this.touchMode ? 'Mouse acts as touch (click to act as a mouse)' : 'Mouse acts as a mouse (click to act as touch)';
  }

  async fullscreen() {
    try {
      await this.stage.requestFullscreen();
      // Lets Esc and other reserved keys reach the program while in full screen (Chromium).
      await navigator.keyboard?.lock?.();
    } catch { /* not allowed */ }
    this.canvas.focus();
  }

  // ---- input
  /** Position inside the picture as fractions (the canvas letterboxes it), or null when outside. */
  position(e, clamp) {
    const rect = this.canvas.getBoundingClientRect();
    if (!this.width || !rect.width) return null;
    const scale = Math.min(rect.width / this.width, rect.height / this.height);
    const w = this.width * scale, hgt = this.height * scale;
    let x = (e.clientX - rect.left - (rect.width - w) / 2) / w;
    let y = (e.clientY - rect.top - (rect.height - hgt) / 2) / hgt;
    if (!clamp && (x < 0 || x > 1 || y < 0 || y > 1)) return null;
    x = Math.min(1, Math.max(0, x));
    y = Math.min(1, Math.max(0, y));
    return { x, y };
  }

  pointerKind(e) { return e.pointerType === 'touch' || this.touchMode ? 'touch' : 'mouse'; }

  touchId(e, create) {
    if (!this.touchIds.has(e.pointerId)) {
      if (!create) return null;
      const used = new Set(this.touchIds.values());
      let id = 0;
      while (used.has(id) && id < 9) id++;
      this.touchIds.set(e.pointerId, id);
    }
    return this.touchIds.get(e.pointerId);
  }

  bindInput() {
    const c = this.canvas;
    c.addEventListener('contextmenu', (e) => e.preventDefault());
    c.addEventListener('pointerdown', (e) => {
      const p = this.position(e, false);
      c.focus();
      if (!p) return;
      e.preventDefault();
      c.setPointerCapture(e.pointerId);
      const k = this.pointerKind(e);
      this.sendPointer({ a: 'down', ...p, b: k === 'touch' ? 1 : e.buttons, id: k === 'touch' ? this.touchId(e, true) : 0, k });
    });
    c.addEventListener('pointermove', (e) => {
      const k = this.pointerKind(e);
      const pressed = k === 'touch' ? this.touchIds.has(e.pointerId) : e.buttons !== 0;
      if (k === 'touch' && !pressed) return;
      const p = this.position(e, pressed);
      if (!p) return;
      // One move per animation frame is plenty and keeps the socket quiet.
      const first = !this.pendingMove;
      this.pendingMove = { a: pressed ? 'move' : 'hover', ...p, b: k === 'touch' ? 1 : e.buttons, id: k === 'touch' ? this.touchId(e, false) : 0, k };
      if (first) requestAnimationFrame(() => { const m = this.pendingMove; this.pendingMove = null; if (m) this.sendPointer(m); });
    });
    const up = (cancel) => (e) => {
      const k = this.pointerKind(e);
      if (k === 'touch' && !this.touchIds.has(e.pointerId)) return;
      const p = this.position(e, true) || { x: 0, y: 0 };
      if (this.pendingMove) { this.sendPointer(this.pendingMove); this.pendingMove = null; }
      this.sendPointer({ a: cancel ? 'cancel' : 'up', ...p, b: k === 'touch' ? 0 : e.buttons, id: k === 'touch' ? this.touchId(e, false) : 0, k });
      if (k === 'touch') this.touchIds.delete(e.pointerId);
    };
    c.addEventListener('pointerup', up(false));
    c.addEventListener('pointercancel', up(true));
    c.addEventListener('wheel', (e) => {
      const p = this.position(e, false);
      if (!p) return;
      e.preventDefault();
      const unit = e.deltaMode === 1 ? 1 : e.deltaMode === 2 ? 10 : 1 / 100; // pixels -> lines
      this.sendWheel(p.x, p.y, e.deltaX * unit, e.deltaY * unit);
    }, { passive: false });
    const key = (down) => (e) => {
      if (e.key === 'F11' || e.key === 'F12') return; // the browser's full screen and developer tools
      e.preventDefault();
      e.stopPropagation(); // not the IDE's shortcuts
      const m = (e.shiftKey ? MOD_SHIFT : 0) | (e.ctrlKey ? MOD_CTRL : 0) | (e.altKey ? MOD_ALT : 0) | (e.metaKey ? MOD_META : 0)
        | (e.getModifierState?.('CapsLock') ? MOD_CAPS : 0);
      this.send({ t: 'key', d: down, code: e.code, key: e.key, m, r: down && e.repeat });
    };
    c.addEventListener('keydown', key(true));
    c.addEventListener('keyup', key(false));
    c.addEventListener('blur', () => this.releaseKeys());
  }

  focus() { this.canvas.focus(); }

  dispose() {
    this.disposed = true;
    clearInterval(this.statsTimer);
    clearTimeout(this.resizeTimer);
    this.resizeObserver.disconnect();
    this.releaseKeys();
    try { this.ws?.close(1000); } catch { /* closed */ }
    try { this.decoder?.close(); } catch { /* closed */ }
    this.el.remove();
  }
}
