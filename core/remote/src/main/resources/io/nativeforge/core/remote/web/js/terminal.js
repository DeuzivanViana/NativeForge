// A terminal tab: xterm.js attached to a pseudo-terminal on the phone. The session keeps
// running when the connection drops; reconnecting replays its recent output.
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { api, wsUrl } from './api.js';
import { terminalTheme } from './theme.js';

export class TerminalView {
  constructor(host, id, theme, { onExit } = {}) {
    this.id = id;
    this.host = host;
    this.onExit = onExit;
    this.exited = false;
    this.disposed = false;
    this.retry = 500;
    this.term = new Terminal({
      fontFamily: getComputedStyle(document.documentElement).getPropertyValue('--mono').trim() || 'monospace',
      fontSize: Math.round((theme.terminalFontSize || 12) * 1.1),
      theme: terminalTheme(theme),
      cursorBlink: true,
      scrollback: 5000,
      allowProposedApi: false,
      convertEol: false,
    });
    this.fit = new FitAddon();
    this.term.loadAddon(this.fit);
    this.term.open(host);
    this.encoder = new TextEncoder();
    this.term.onData((data) => this.send(this.encoder.encode(data)));
    this.term.onBinary((data) => this.send(Uint8Array.from(data, (c) => c.charCodeAt(0) & 0xff)));
    this.term.onResize(({ rows, cols }) => this.control({ t: 'resize', rows, cols }));
    this.resizer = new ResizeObserver(() => this.refit());
    this.resizer.observe(host);
    this.connect();
  }

  connect() {
    if (this.disposed) return;
    const ws = new WebSocket(wsUrl(`/ws/terminal?id=${encodeURIComponent(this.id)}`));
    ws.binaryType = 'arraybuffer';
    this.ws = ws;
    let first = true;
    ws.onopen = () => {
      this.retry = 500;
        this.refit();
      this.control({ t: 'resize', rows: this.term.rows, cols: this.term.cols });
    };
    ws.onmessage = (e) => {
      if (typeof e.data === 'string') {
        try {
          const msg = JSON.parse(e.data);
          if (msg.t === 'exit' && !this.exited) {
            this.exited = true;
            this.term.write(`\r\n\x1b[2m[process exited with code ${msg.code}]\x1b[0m\r\n`);
            this.onExit?.(msg.code);
          }
        } catch { /* ignore */ }
        return;
      }
      if (first) { first = false; this.term.reset(); } // the server replays recent output on every (re)connect
      this.term.write(new Uint8Array(e.data));
    };
    ws.onclose = (e) => {
      if (this.disposed || this.exited || e.code === 1000 || e.code === 4001) return;
      setTimeout(() => this.reconnect(), this.retry);
      this.retry = Math.min(this.retry * 2, 8000);
    };
  }

  /** Reconnects unless the phone no longer has this session (for example after the app restarted). */
  async reconnect() {
    if (this.disposed || this.exited) return;
    try {
      const list = await api('/api/terminals');
      if (!list.some((t) => t.id === this.id)) {
        this.exited = true;
        this.term.write('\r\n\x1b[2m[terminal closed on the phone]\x1b[0m\r\n');
        this.onExit?.(null);
        return;
      }
    } catch { /* phone unreachable: keep trying */ }
    this.connect();
  }

  send(bytes) { if (this.ws?.readyState === WebSocket.OPEN && !this.exited) this.ws.send(bytes); }
  control(msg) { if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg)); }

  refit() {
    if (!this.host.offsetParent) return; // hidden tab
    try { this.fit.fit(); } catch { /* not laid out yet */ }
  }

  setTheme(theme) {
    this.term.options.theme = terminalTheme(theme);
    this.term.options.fontSize = Math.round((theme.terminalFontSize || 12) * 1.1);
    this.refit();
  }

  focus() { this.term.focus(); }

  dispose() {
    this.disposed = true;
    this.resizer.disconnect();
    this.ws?.close(1000);
    this.term.dispose();
  }
}
