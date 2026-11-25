// DOM helpers, icons (Material Icons paths, Apache-2.0) and small UI primitives.

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k in el && k !== 'list') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

/** replaceChildren that skips null/false (the DOM would insert the text "null"). */
export function fill(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

const PATHS = {
  play: 'M8 5v14l11-7z',
  stop: 'M6 6h12v12H6z',
  close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  add: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
  folder: 'M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
  file: 'M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
  delete: 'M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z',
  back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
  terminal: 'M20 4H4c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.89-2-2-2zm0 14H4V8h16v10zm-2-1h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z',
  build: 'M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z',
  refresh: 'M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
  download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
  upload: 'M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z',
  more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
  right: 'M10 6 8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z',
  down: 'M16.59 8.59 12 13.17 7.41 8.59 6 10l6 6 6-6z',
  newFolder: 'M20 6h-8l-2-2H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2zm-1 8h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z',
  newFile: 'M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 14h-3v3h-2v-3H8v-2h3v-3h2v3h3v2zm-3-7V3.5L18.5 9H13z',
  edit: 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
  save: 'M17 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zm-5 16c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zm3-10H5V5h10v4z',
  logout: 'M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z',
  phone: 'M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z',
  error: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z',
  warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
  code: 'M9.4 16.6 4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0 4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z',
  layers: 'm11.99 18.54-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z',
  game: 'M21 6H3c-1.1 0-2 .9-2 2v8c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-10 7H8v3H6v-3H3v-2h3V8h2v3h3v2zm4.5 2c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm4-3c-.83 0-1.5-.67-1.5-1.5S18.67 9 19.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z',
  panel: 'M3 3h18v18H3V3zm2 2v9h14V5H5z',
  search: 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
  fullscreen: 'M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z',
  mouse: 'M13 1.07V9h7c0-4.08-3.05-7.44-7-7.93zM4 15c0 4.42 3.58 8 8 8s8-3.58 8-8v-4H4v4zm7-13.93C7.05 1.56 4 4.92 4 9h7V1.07z',
  touch: 'M9 11.24V7.5C9 6.12 10.12 5 11.5 5S14 6.12 14 7.5v3.74c1.21-.81 2-2.18 2-3.74C16 5.01 13.99 3 11.5 3S7 5.01 7 7.5c0 1.56.79 2.93 2 3.74zm9.84 4.63-4.54-2.26c-.17-.07-.35-.11-.54-.11H13v-6c0-.83-.67-1.5-1.5-1.5S10 6.67 10 7.5v10.74l-3.43-.72c-.08-.01-.15-.03-.24-.03-.31 0-.59.13-.79.33l-.79.8 4.94 4.94c.27.27.65.44 1.06.44h6.79c.75 0 1.33-.55 1.44-1.28l.75-5.27c.01-.07.02-.14.02-.2 0-.62-.38-1.16-.91-1.38z',
  monitor: 'M21 2H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h7v2H8v2h8v-2h-2v-2h7c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H3V4h18v12z',
  check: 'M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z',
  settings: 'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
  copy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
  cut: 'M9.64 7.64c.23-.5.36-1.05.36-1.64 0-2.21-1.79-4-4-4S2 3.79 2 6s1.79 4 4 4c.59 0 1.14-.13 1.64-.36L10 12l-2.36 2.36C7.14 14.13 6.59 14 6 14c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4c0-.59-.13-1.14-.36-1.64L12 14l7 7h3v-1L9.64 7.64zM6 8c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm0 12c-1.1 0-2-.89-2-2s.9-2 2-2 2 .89 2 2-.9 2-2 2zm6-7.5c-.28 0-.5-.22-.5-.5s.22-.5.5-.5.5.22.5.5-.22.5-.5.5zM19 3l-6 6 2 2 7-7V3z',
  paste: 'M19 2h-4.18C14.4.84 13.3 0 12 0c-1.3 0-2.4.84-2.82 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z',
  selectAll: 'M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z',
  comment: 'M21.99 4c0-1.1-.89-2-1.99-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4-.01-18zM18 14H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z',
  format: 'M3 21h18v-2H3v2zm0-4h12v-2H3v2zm0-4h18v-2H3v2zm0-4h12V7H3v2zm0-6v2h18V3H3z',
  jump: 'M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z',
  line: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
  collapse: 'M7.41 18.59 8.83 20 12 16.83 15.17 20l1.41-1.41L12 14l-4.59 4.59zm9.18-13.18L15.17 4 12 7.17 8.83 4 7.41 5.41 12 10l4.59-4.59z',
  undo: 'M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z',
  redo: 'M18.4 10.6C16.55 8.99 14.15 8 11.5 8c-4.65 0-8.58 3.03-9.96 7.22L3.9 16c1.05-3.19 4.05-5.5 7.6-5.5 1.95 0 3.73.72 5.12 1.88L13 16h9V7l-3.6 3.6z',
  minus: 'M19 13H5v-2h14v2z',
  external: 'M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z',
};

export function icon(name, cls = 'i') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', cls);
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', PATHS[name] || PATHS.file);
  svg.append(path);
  return svg;
}

export function iconButton(name, title, onClick, cls = '') {
  return h('button', { class: `icon-btn ${cls}`, title, 'aria-label': title, onclick: onClick }, icon(name));
}

let toastHost;
export function toast(message, kind = '') {
  if (!toastHost) { toastHost = h('div', { class: 'toasts' }); document.body.append(toastHost); }
  const el = h('div', { class: `toast ${kind}` }, message);
  toastHost.append(el);
  setTimeout(() => el.remove(), kind === 'err' ? 7000 : 3500);
}

/** A modal dialog; resolves with the value passed to close(), or null when dismissed. */
export function dialog({ title, body, actions, wide }) {
  return new Promise((resolve) => {
    const close = (value = null) => { scrim.remove(); document.removeEventListener('keydown', onKey, true); resolve(value); };
    const onKey = (e) => { if (e.key === 'Escape') { e.stopPropagation(); close(null); } };
    const content = typeof body === 'function' ? body(close) : body;
    const buttons = (actions || []).map((a) => h('button', {
      class: `btn ${a.primary ? 'primary' : ''} ${a.danger ? 'danger' : ''}`,
      onclick: () => (a.onClick ? a.onClick(close) : close(a.value ?? null)),
    }, a.label));
    const box = h('div', { class: `dialog ${wide ? 'wide' : ''}`, role: 'dialog', 'aria-modal': 'true' },
      h('div', { class: 'dh' }, title), h('div', { class: 'db' }, content), buttons.length ? h('div', { class: 'df' }, buttons) : null);
    const scrim = h('div', { class: 'scrim', onmousedown: (e) => { if (e.target === scrim) close(null); } }, box);
    document.addEventListener('keydown', onKey, true);
    document.body.append(scrim);
    setTimeout(() => box.querySelector('input, select, textarea, .btn.primary')?.focus(), 0);
  });
}

export async function prompt(title, { value = '', label = '', placeholder = '', ok = 'OK', select } = {}) {
  const input = h('input', { class: 'field', value, placeholder, spellcheck: false });
  const result = dialog({
    title,
    body: (close) => {
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') close(input.value); });
      return h('div', null, label ? h('label', { class: 'lbl' }, label) : null, input);
    },
    actions: [{ label: 'Cancel' }, { label: ok, primary: true, onClick: (close) => close(input.value) }],
  });
  setTimeout(() => { input.focus(); if (select) input.setSelectionRange(...select(value)); else input.select(); }, 0);
  return result;
}

export function confirm(title, message, okLabel = 'OK', danger = false) {
  return dialog({ title, body: h('p', null, message), actions: [{ label: 'Cancel', value: false }, { label: okLabel, primary: !danger, danger, value: true }] });
}

/** The interface zoom (Settings › Interface size): CSS pixels inside the page are this many screen pixels. */
export function uiZoom() {
  const z = parseFloat(document.documentElement.style.zoom);
  return z > 0 ? z : 1;
}

export const isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** "Ctrl+Shift+F" as this platform writes it ("⇧⌘F" on a Mac). */
export function keyHint(keys) {
  if (!keys || !isMac) return keys;
  return keys.replace(/Ctrl\+/g, '⌘').replace(/Shift\+/g, '⇧').replace(/Alt\+/g, '⌥');
}

/**
 * A context menu at [x], [y] (viewport coordinates, as in mouse events).
 * items: { label, icon, hint, onClick, danger, disabled } or 'sep'; null and false are skipped,
 * and separators never stack up or end the menu. Arrow keys move, Enter picks, Escape closes.
 */
export function menu(x, y, items) {
  document.querySelector('.menu')?.dispatchEvent(new Event('nf-close'));
  const list = [];
  for (const it of items) {
    if (!it) continue;
    if (it === 'sep' && (!list.length || list[list.length - 1] === 'sep')) continue;
    list.push(it);
  }
  while (list[list.length - 1] === 'sep') list.pop();
  if (!list.length) return null;
  const before = document.activeElement;
  const el = h('div', { class: 'menu', role: 'menu' }, list.map((it) => it === 'sep' ? h('hr') : h('button', {
    class: it.danger ? 'danger' : '', role: 'menuitem', disabled: !!it.disabled,
    onclick: () => { close(false); it.onClick(); },
  }, it.icon ? icon(it.icon, 'i s') : h('span', { class: 'i s' }), h('span', { class: 'label' }, it.label),
  it.hint ? h('span', { class: 'hint' }, keyHint(it.hint)) : null)));
  const buttons = () => [...el.querySelectorAll('button:not(:disabled)')];
  const dismiss = (e) => { if (!el.contains(e.target)) close(false); };
  const onKey = (e) => {
    const all = buttons();
    const at = all.indexOf(document.activeElement);
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(true); }
    else if (e.key === 'ArrowDown') { e.preventDefault(); all[(at + 1) % all.length]?.focus(); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); all[at < 0 ? all.length - 1 : (at - 1 + all.length) % all.length]?.focus(); }
    else if (e.key === 'Home') { e.preventDefault(); all[0]?.focus(); }
    else if (e.key === 'End') { e.preventDefault(); all[all.length - 1]?.focus(); }
    else if (e.key === 'Tab') { e.preventDefault(); close(true); }
  };
  const onLeave = () => close(false);
  function close(restoreFocus) {
    if (!el.isConnected) return;
    el.remove();
    removeEventListener('mousedown', dismiss, true);
    removeEventListener('contextmenu', dismiss, true);
    removeEventListener('keydown', onKey, true);
    removeEventListener('resize', onLeave);
    removeEventListener('blur', onLeave);
    if (restoreFocus) before?.focus?.();
  }
  el.addEventListener('nf-close', () => close(false));
  el.addEventListener('contextmenu', (e) => e.preventDefault());
  document.body.append(el);
  // The page may be zoomed (Interface size): mouse and layout positions are screen pixels, styles are CSS pixels.
  const z = uiZoom();
  const r = el.getBoundingClientRect();
  el.style.left = `${Math.max(4, Math.min(x, innerWidth - r.width - 8)) / z}px`;
  el.style.top = `${Math.max(4, Math.min(y, innerHeight - r.height - 8)) / z}px`;
  addEventListener('keydown', onKey, true);
  addEventListener('resize', onLeave);
  addEventListener('blur', onLeave);
  setTimeout(() => {
    addEventListener('mousedown', dismiss, true);
    addEventListener('contextmenu', dismiss, true);
  }, 0);
  el.tabIndex = -1;
  el.focus({ preventScroll: true }); // the keys reach the menu; arrows then walk its items
  return el;
}

/** Copies [text]; falls back to a hidden text field where the clipboard API is missing. */
export async function copyText(text, what = 'Copied') {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const area = h('textarea', { value: text, style: { position: 'fixed', opacity: '0', top: '0' } });
    document.body.append(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    if (!ok) { toast('The browser blocked the clipboard', 'err'); return false; }
  }
  if (what) toast(what);
  return true;
}

/** Text from the clipboard, or null (with a hint) when the browser does not allow reading it. */
export async function readClipboard() {
  try {
    return await navigator.clipboard.readText();
  } catch {
    toast(`The browser blocked reading the clipboard: paste with ${keyHint('Ctrl+V')}`, 'err');
    return null;
  }
}

/** Makes [handle] resize [target] along [axis]; the size is remembered under [key]. */
export function resizable(handle, target, axis, key, { min = 80, max = 2000, invert = false } = {}) {
  const prop = axis === 'x' ? 'width' : 'height';
  try { const saved = localStorage.getItem(key); if (saved) target.style[prop] = `${saved}px`; } catch { /* storage blocked */ }
  handle.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);
    handle.classList.add('dragging');
    const z = uiZoom(); // mouse positions are screen pixels, sizes CSS pixels
    const start = axis === 'x' ? e.clientX : e.clientY;
    const startSize = target.getBoundingClientRect()[prop] / z;
    const move = (ev) => {
      const delta = ((axis === 'x' ? ev.clientX : ev.clientY) - start) / z;
      target.style[prop] = `${Math.max(min, Math.min(max, startSize + (invert ? -delta : delta)))}px`;
      dispatchEvent(new Event('resize'));
    };
    const up = () => {
      handle.classList.remove('dragging');
      handle.removeEventListener('pointermove', move);
      try { localStorage.setItem(key, String(Math.round(target.getBoundingClientRect()[prop] / uiZoom()))); } catch { /* ignore */ }
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up, { once: true });
  });
}

export function debounce(fn, ms) {
  let t;
  return (...args) => { clearTimeout(t); t = setTimeout(() => fn(...args), ms); };
}

export function basename(path) { return path.slice(path.lastIndexOf('/') + 1); }
export function dirname(path) { const i = path.lastIndexOf('/'); return i < 0 ? '' : path.slice(0, i); }
