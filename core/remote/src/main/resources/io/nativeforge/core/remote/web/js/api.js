// HTTP API and the event WebSocket. The session cookie is HttpOnly: scripts never see it.

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

let onUnauthorized = () => {};
export function setUnauthorizedHandler(fn) { onUnauthorized = fn; }

export async function api(path, { method = 'GET', body, query, raw } = {}) {
  const url = new URL(path, location.origin);
  if (query) for (const [k, v] of Object.entries(query)) if (v != null) url.searchParams.set(k, v);
  const init = { method, credentials: 'same-origin', headers: {} };
  if (raw !== undefined) { init.body = raw; init.headers['Content-Type'] = 'application/octet-stream'; }
  else if (body !== undefined) { init.body = JSON.stringify(body); init.headers['Content-Type'] = 'application/json'; }
  let response;
  try { response = await fetch(url, init); }
  catch { throw new ApiError(0, 'The phone is not reachable'); }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = null; }
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/pair') onUnauthorized();
    throw new ApiError(response.status, data?.error || `${response.status} ${response.statusText}`);
  }
  return data;
}

export function wsUrl(path) {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${path}`;
}

/** The /ws channel: server events plus request/reply calls; reconnects with backoff. */
export class Channel {
  constructor() {
    this.handlers = new Map();
    this.pending = new Map();
    this.nextId = 1;
    this.retry = 500;
    this.queue = [];
    this.closed = false;
  }

  on(type, fn) {
    if (!this.handlers.has(type)) this.handlers.set(type, new Set());
    this.handlers.get(type).add(fn);
    return () => this.handlers.get(type).delete(fn);
  }

  emit(type, msg) { this.handlers.get(type)?.forEach((fn) => { try { fn(msg); } catch (e) { console.error(e); } }); }

  connect() {
    if (this.closed) return;
    const ws = new WebSocket(wsUrl('/ws'));
    this.ws = ws;
    ws.onopen = () => {
      this.retry = 500;
      this.emit('open', {});
      for (const m of this.queue.splice(0)) ws.send(m);
      clearInterval(this.pinger);
      this.pinger = setInterval(() => this.send({ t: 'ping' }), 25000);
    };
    ws.onmessage = (e) => {
      let msg;
      try { msg = JSON.parse(e.data); } catch { return; }
      if (msg.t === 'reply') {
        const p = this.pending.get(msg.id);
        if (p) { this.pending.delete(msg.id); msg.error ? p.reject(new Error(msg.error)) : p.resolve(msg.result); }
        return;
      }
      this.emit(msg.t, msg);
    };
    ws.onclose = (e) => {
      clearInterval(this.pinger);
      for (const p of this.pending.values()) p.reject(new Error('Disconnected'));
      this.pending.clear();
      this.emit('close', { code: e.code });
      if (e.code === 4001) { onUnauthorized(); return; }
      if (this.closed) return;
      setTimeout(() => this.connect(), this.retry);
      this.retry = Math.min(this.retry * 2, 8000);
    };
  }

  get connected() { return this.ws?.readyState === WebSocket.OPEN; }

  send(msg) {
    const text = JSON.stringify(msg);
    if (this.connected) this.ws.send(text);
    else if (msg.t !== 'ping' && !msg.t.startsWith('lsp.')) this.queue.push(text);
  }

  request(type, payload, timeoutMs = 15000) {
    if (!this.connected) return Promise.reject(new Error('Disconnected'));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ t: type, id, ...payload }));
      setTimeout(() => { if (this.pending.delete(id)) reject(new Error('Timed out')); }, timeoutMs);
    });
  }

  close() { this.closed = true; clearInterval(this.pinger); this.ws?.close(); }
}
