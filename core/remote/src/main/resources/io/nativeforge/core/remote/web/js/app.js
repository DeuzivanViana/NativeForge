// NativeForge in the browser: pairing, projects, and the workspace (explorer, editor,
// build output, problems, terminals). Everything runs on the phone; this page is the UI.
import { api, ApiError, Channel, setUnauthorizedHandler } from './api.js';
import { h, fill, icon, iconButton, toast, dialog, prompt, confirm, menu, resizable, basename, dirname } from './util.js';
import { applyPalette } from './theme.js';
import { FileEditor } from './editor.js';
import { TerminalView } from './terminal.js';
import { ScreenView } from './screen.js';

const root = document.getElementById('app');
const app = { theme: null, projects: [], templates: [], channel: null, workspace: null, device: '' };

setUnauthorizedHandler(() => { if (app.channel) { app.channel.close(); app.channel = null; } showPairing(); });

start();

async function start() {
  const pairCode = new URLSearchParams(location.hash.slice(1)).get('pair');
  if (pairCode) history.replaceState(null, '', location.pathname); // keep the code out of history
  let hello;
  try { hello = await api('/api/hello'); } catch (e) { return fatal(e.message); }
  app.device = hello.device;
  if (!hello.authenticated) return showPairing(pairCode, hello);
  await boot();
}

function fatal(message) {
  fill(root, h('div', { class: 'pair' }, h('div', { class: 'logo' }, icon('code'), 'NativeForge'), h('h1', null, 'Not connected'), h('p', null, message),
    h('button', { class: 'btn primary', onclick: () => location.reload() }, 'Retry')));
}

// ------------------------------------------------------------------ pairing

function showPairing(prefill, hello) {
  app.workspace?.dispose();
  app.workspace = null;
  const input = h('input', { class: 'field code', maxLength: 9, autocomplete: 'one-time-code', spellcheck: false, placeholder: 'XXXX-XXXX', 'aria-label': 'Pairing code' });
  const error = h('div', { class: 'error-text' });
  const submit = h('button', { class: 'btn primary', style: { width: '100%', justifyContent: 'center', marginTop: '12px', height: '36px' } }, 'Connect');
  const go = async () => {
    const code = input.value.replace(/[^0-9a-z]/gi, '');
    if (code.length < 8) { error.textContent = 'Type the 8-character code shown in the app.'; return; }
    submit.disabled = true;
    error.textContent = '';
    try {
      await api('/api/pair', { method: 'POST', body: { code } });
      await boot();
    } catch (e) {
      error.textContent = e.message;
      submit.disabled = false;
      input.select();
    }
  };
  input.addEventListener('input', () => {
    const raw = input.value.toUpperCase().replace(/[^0-9A-Z]/g, '').slice(0, 8);
    input.value = raw.length > 4 ? `${raw.slice(0, 4)}-${raw.slice(4)}` : raw;
  });
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  submit.onclick = go;
  fill(root, h('div', { class: 'pair' },
    h('div', { class: 'logo' }, icon('code'), 'NativeForge', hello?.device ? h('span', { class: 'sub', style: { marginLeft: 'auto' } }, hello.device) : null),
    h('h1', null, 'Pair this browser'),
    h('ol', null,
      h('li', null, 'On the phone, open NativeForge › Remote access.'),
      h('li', null, 'Check that the certificate fingerprint your browser shows matches the one in the app.'),
      h('li', null, 'Type the pairing code below. Each code works once and expires after 5 minutes.')),
    input, submit, error,
    h('p', { style: { fontSize: '12px', marginTop: '12px' } }, 'The phone does the work: files, builds and terminals stay on the device. This browser stays signed in for 30 days unless you revoke it in the app.')));
  if (prefill) { input.value = prefill; input.dispatchEvent(new Event('input')); go(); } else input.focus();
}

// ------------------------------------------------------------------ boot

async function boot() {
  const data = await api('/api/bootstrap');
  app.theme = data.theme;
  app.projects = data.projects;
  app.templates = data.templates;
  app.device = data.device;
  app.bootBuild = data.build;
  app.bootTerminals = data.terminals;
  app.intel = data.intel;
  app.screen = data.screen; // the program shown in the browser, if one runs
  applyPalette(app.theme);
  document.title = `NativeForge · ${app.device}`;
  app.channel?.close();
  const channel = new Channel();
  app.channel = channel;
  channel.on('theme', (m) => { app.theme = m.theme; applyPalette(app.theme); app.workspace?.setTheme(app.theme); });
  channel.on('open', () => document.querySelector('.offline')?.remove());
  channel.on('screen.start', (m) => { app.screen = { id: m.id, title: m.title }; });
  channel.on('screen.end', (m) => { if (app.screen?.id === m.id) app.screen = null; });
  channel.on('close', () => {
    if (!document.querySelector('.offline')) document.body.append(h('div', { class: 'offline' }, 'Reconnecting to the phone…'));
  });
  channel.connect();
  if (!app.routing) { addEventListener('hashchange', route); app.routing = true; }
  route();
}

function route() {
  const m = location.hash.match(/^#\/p\/(.+)$/);
  if (m) {
    const key = decodeURIComponent(m[1]);
    if (app.workspace?.key === key) return;
    app.workspace?.dispose();
    app.workspace = new Workspace(key);
  } else {
    app.workspace?.dispose();
    app.workspace = null;
    showHome();
  }
}

// ------------------------------------------------------------------ home

const RUNTIME_ICON = { CONSOLE: ['terminal', 'var(--success)'], SDL3: ['layers', 'var(--secondary)'], SDL2: ['layers', 'var(--secondary)'], NATIVE_ACTIVITY: ['game', 'var(--accent)'] };

async function showHome() {
  const grid = h('div', { class: 'grid' });
  const filter = h('input', { class: 'field', placeholder: 'Filter projects', style: { maxWidth: '260px', height: '30px' } });
  const render = () => {
    const q = filter.value.toLowerCase();
    const list = app.projects.filter((p) => p.name.toLowerCase().includes(q));
    fill(grid, ...list.map(projectCard));
    if (!app.projects.length) fill(grid, h('div', { class: 'empty', style: { gridColumn: '1/-1' } }, 'No projects yet. Create one to start.'));
  };
  filter.addEventListener('input', render);
  fill(root, 
    h('div', { class: 'topbar' },
      h('span', { class: 'title' }, 'NativeForge'), h('span', { class: 'sub' }, app.device),
      h('div', { class: 'spacer' }),
      iconButton('add', 'New project', () => newProjectDialog(), 'accent'),
      iconButton('terminal', 'Terminal', () => openStandaloneTerminal()),
      iconButton('refresh', 'Refresh', async () => { app.projects = await api('/api/projects'); render(); }),
      iconButton('logout', 'Sign out this browser', async () => {
        if (await confirm('Sign out', 'This browser will need a new pairing code to connect again.', 'Sign out')) {
          await api('/api/logout', { method: 'POST', body: {} }).catch(() => {});
          location.reload();
        }
      })),
    h('div', { class: 'home' }, h('div', { class: 'home-inner' },
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px' } },
        h('h2', { style: { margin: 0 } }, 'Projects'), h('div', { class: 'spacer' }), filter,
        h('button', { class: 'btn primary', onclick: () => newProjectDialog() }, icon('add', 'i s'), 'New project')),
      grid)));
  render();
  try { app.projects = await api('/api/projects'); render(); } catch { /* keep bootstrap list */ }
}

function projectCard(p) {
  const [ic, color] = RUNTIME_ICON[p.runtime] || ['code', 'var(--accent)'];
  const more = iconButton('more', 'Project actions', (e) => {
    e.stopPropagation();
    const r = e.currentTarget.getBoundingClientRect();
    menu(r.left, r.bottom, [
      { label: 'Open', icon: 'folder', onClick: () => openProject(p.key) },
      'sep',
      p.external
        ? { label: 'Remove from list', icon: 'close', onClick: () => removeProject(p) }
        : { label: 'Delete', icon: 'delete', danger: true, onClick: () => removeProject(p) },
    ]);
  });
  return h('div', { class: 'card', onclick: () => openProject(p.key), title: p.path },
    h('div', { class: 'badge', style: { color } }, icon(ic)),
    h('div', { class: 'meta' },
      h('div', { class: 'name' }, p.name),
      h('div', { class: 'info' }, [p.language === 'C' ? 'C' : 'C++', p.standard, p.buildSystem, p.external ? 'Device folder' : null].filter(Boolean).join(' · '))),
    more);
}

const openProject = (key) => { location.hash = `#/p/${encodeURIComponent(key)}`; };

async function removeProject(p) {
  const ok = p.external
    ? await confirm('Remove from list', `${p.name} stays in its folder on the phone; only its build files are deleted.`, 'Remove')
    : await confirm('Delete project', `Delete ${p.name} and all its files from the phone? This cannot be undone.`, 'Delete', true);
  if (!ok) return;
  try {
    await api('/api/projects/remove', { method: 'POST', body: { project: p.key } });
    app.projects = app.projects.filter((x) => x.key !== p.key);
    showHome();
  } catch (e) { toast(e.message, 'err'); }
}

async function newProjectDialog() {
  let selected = app.templates[0];
  const name = h('input', { class: 'field', placeholder: 'my-project', spellcheck: false });
  const list = h('div', { class: 'tpl-list' });
  const details = h('div', { class: 'tpl-details' });
  const options = h('div');
  const state = { language: null, buildSystem: null, standard: null };
  const seg = (items, value, onPick) => h('div', { class: 'seg' }, items.map((it) => h('button', {
    class: it.id === value ? 'on' : '', onclick: () => onPick(it.id),
  }, it.name)));
  const renderOptions = () => {
    const t = selected;
    if (!t.languages.includes(state.language)) state.language = t.languages[0];
    if (!t.buildSystems.some((b) => b.id === state.buildSystem)) state.buildSystem = t.buildSystems[0].id;
    const standards = t.standards[state.language] || [];
    if (!standards.includes(state.standard)) state.standard = standards[standards.length > 2 ? standards.length - 2 : standards.length - 1];
    fill(options, 
      h('label', { class: 'lbl' }, 'Language'),
      seg(t.languages.map((l) => ({ id: l, name: l === 'C' ? 'C' : 'C++' })), state.language, (v) => { state.language = v; renderOptions(); }),
      h('label', { class: 'lbl' }, 'Standard'),
      seg(standards.map((s) => ({ id: s, name: s })), state.standard, (v) => { state.standard = v; renderOptions(); }),
      h('label', { class: 'lbl' }, 'Build system'),
      seg(t.buildSystems, state.buildSystem, (v) => { state.buildSystem = v; renderOptions(); }),
      t.packages.length ? h('p', { style: { marginTop: '10px' } }, `Libraries: ${t.packages.join(', ')}`) : null);
    details.textContent = t.details;
  };
  const renderList = () => {
    const groups = {};
    for (const t of app.templates) (groups[t.category] ||= []).push(t);
    fill(list, ...Object.entries(groups).flatMap(([cat, items]) => [
      h('div', { class: 'tpl-cat' }, cat),
      ...items.map((t) => h('div', { class: `tpl ${t === selected ? 'sel' : ''}`, onclick: () => { selected = t; renderList(); renderOptions(); } },
        h('div', null, t.name), h('div', { class: 'd' }, t.description))),
    ]));
  };
  renderList();
  renderOptions();
  const result = await dialog({
    title: 'New project',
    wide: true,
    body: h('div', null,
      h('label', { class: 'lbl', style: { marginTop: 0 } }, 'Name'), name,
      h('div', { class: 'tpl-layout', style: { marginTop: '12px' } },
        h('div', null, h('label', { class: 'lbl', style: { marginTop: 0 } }, 'Template'), list),
        h('div', null, details, options))),
    actions: [{ label: 'Cancel' }, {
      label: 'Create', primary: true,
      onClick: async (close) => {
        try {
          const p = await api('/api/projects', { method: 'POST', body: { name: name.value.trim(), template: selected.id, ...state } });
          close(p);
        } catch (e) { toast(e.message, 'err'); }
      },
    }],
  });
  if (result) { app.projects.unshift(result); openProject(result.key); }
}

async function openStandaloneTerminal() {
  const view = h('div', { class: 'term-host', style: { position: 'absolute', inset: '0' } });
  let term;
  try {
    const { id } = await api('/api/terminals', { method: 'POST', body: { rows: 30, cols: 100 } });
    await dialog({
      title: 'Terminal',
      wide: true,
      body: () => { const box = h('div', { style: { position: 'relative', height: '60vh' } }, view); setTimeout(() => { term = new TerminalView(view, id, app.theme); term.focus(); }, 0); return box; },
      actions: [{ label: 'Close', onClick: (close) => close(true) }],
    });
    term?.dispose();
    api('/api/terminals/close', { method: 'POST', body: { id } }).catch(() => {});
  } catch (e) { toast(e.message, 'err'); }
}

// ------------------------------------------------------------------ workspace

class Workspace {
  constructor(key) {
    this.key = key;
    this.entries = [];
    this.expanded = new Set(JSON.parse(sessionStorage.getItem(`nf-exp:${key}`) || '[]'));
    this.tabs = []; // { path, editor, el, modified }
    this.active = null;
    this.diagnostics = new Map(); // path -> clangd diagnostics
    this.buildProblems = [];
    this.terminals = []; // { id, title, view, tab, host }
    this.screen = null; // ScreenView of a graphical program
    try { this.runOn = localStorage.getItem('nf-run-on') || 'browser'; } catch { this.runOn = 'browser'; }
    this.panelTab = 'output';
    this.offs = [];
    this.render();
    this.load();
  }

  // ---- layout
  render() {
    const ch = app.channel;
    this.buildBtn = iconButton('build', 'Build (Ctrl+Shift+B)', () => this.build(false));
    this.runBtn = iconButton('play', 'Build and run (F5)', () => this.build(true), 'success');
    this.runOnBtn = iconButton(this.runOn === 'phone' ? 'phone' : 'monitor', '', (e) => this.chooseRunTarget(e));
    this.updateRunOn();
    this.stopBtn = iconButton('stop', 'Cancel build', () => ch.send({ t: 'cancel' }), 'danger');
    this.stopBtn.style.display = 'none';
    this.status = h('span', { class: 'chip' }, 'Idle');
    this.titleEl = h('span', { class: 'title' }, '…');
    this.tree = h('div', { class: 'tree', tabIndex: 0 });
    this.explorer = h('div', { class: 'explorer' },
      h('div', { class: 'head' }, h('span', null, 'Explorer'), h('div', { class: 'spacer' }),
        iconButton('newFile', 'New file', () => this.newEntry('', false)),
        iconButton('newFolder', 'New folder', () => this.newEntry('', true)),
        iconButton('upload', 'Upload files', () => this.upload('')),
        iconButton('refresh', 'Refresh', () => this.loadTree())),
      this.tree);
    this.tabBar = h('div', { class: 'tabs' });
    this.editorArea = h('div', { class: 'editor-area' });
    this.placeholder = h('div', { class: 'placeholder' }, icon('code'), h('div', null, 'Open a file from the explorer'),
      h('div', null, h('span', { class: 'kbd' }, 'Ctrl+P'), ' quick open · ', h('span', { class: 'kbd' }, 'F5'), ' build and run'));
    this.editorArea.append(this.placeholder);
    this.panelTabs = h('div', { class: 'panel-tabs' });
    this.panelBody = h('div', { class: 'panel-body' });
    this.log = h('div', { class: 'log' });
    this.problemsEl = h('div', { class: 'problems' });
    this.panelBody.append(this.log, this.problemsEl);
    this.panel = h('div', { class: 'panel' }, this.panelTabs, this.panelBody);
    const splitV = h('div', { class: 'splitter-v' });
    const splitH = h('div', { class: 'splitter-h' });
    this.cursorEl = h('span', { class: 'item' }, '');
    this.screenItem = h('span', { class: 'item click', style: { display: 'none' }, title: 'Show the running program', onclick: () => app.screen && this.openScreen(app.screen) }, icon('game', 'i s'), '');
    this.intelEl = h('span', { class: 'item', title: 'clangd: completion, live errors, go to definition' }, h('span', { class: 'dotc' }), 'clangd');
    this.problemCount = h('span', { class: 'item click', onclick: () => this.showPanel('problems') }, icon('error', 'i s'), '0', icon('warning', 'i s'), '0');
    this.el = h('div', { class: 'workspace' },
      h('div', { class: 'topbar' },
        iconButton('back', 'Projects', async () => { if (await this.saveAll()) location.hash = '#/'; }),
        this.titleEl, this.status, h('div', { class: 'spacer' }),
        iconButton('save', 'Save (Ctrl+S)', () => this.save()),
        this.buildBtn, this.runBtn, this.runOnBtn, this.stopBtn,
        iconButton('download', 'Download the built program', () => this.downloadArtifact()),
        iconButton('terminal', 'New terminal (Ctrl+Shift+`)', () => this.newTerminal()),
        iconButton('panel', 'Toggle panel (Ctrl+`)', () => this.panel.classList.toggle('hidden'))),
      h('div', { class: 'ws-body' }, this.explorer, splitV,
        h('div', { class: 'center' }, this.tabBar, this.editorArea, splitH, this.panel)),
      h('div', { class: 'statusbar' }, h('span', { class: 'item' }, icon('phone', 'i s'), app.device), this.problemCount, this.intelEl, this.screenItem, h('div', { class: 'spacer' }), this.cursorEl));
    fill(root, this.el);
    resizable(splitV, this.explorer, 'x', 'nf-explorer', { min: 160, max: 700 });
    resizable(splitH, this.panel, 'y', 'nf-panel', { min: 80, max: 1200, invert: true });
    this.renderPanelTabs();
    this.setIntel(app.intel);

    this.offs.push(
      ch.on('build.start', (m) => this.onBuildStart(m)),
      ch.on('build.log', (m) => this.appendLog(m.line, m.err)),
      ch.on('build.diag', (m) => this.addProblem(m.d)),
      ch.on('build.done', (m) => this.onBuildDone(m)),
      ch.on('run.console', (m) => this.attachTerminal(m.id, m.title, true)),
      ch.on('run.device', (m) => { this.appendLog(m.message, !m.ok); toast(m.message, m.ok ? 'ok' : 'err'); }),
      ch.on('screen.start', (m) => this.openScreen(m)),
      ch.on('screen.end', (m) => { if (this.screen?.id === m.id && m.reason) this.appendLog(`Program ended: ${m.reason}`, false); this.updateScreenItem(); }),
      ch.on('diag', (m) => { if (m.project === this.key) this.setDiagnostics(m.path, m.items); }),
      ch.on('intel', (m) => { this.setIntel(m.status); if (m.status === 'failed' && m.message) this.appendLog(`clangd: ${m.message}`, true); }),
      ch.on('fs', (m) => { if (m.project === this.key) this.treeChanged(); }),
      ch.on('open', () => this.resync()),
    );
    this.updateScreenItem();
    if (app.screen) this.openScreen(app.screen);
    this.keys = (e) => this.onKey(e);
    addEventListener('keydown', this.keys);
    this.beforeUnload = (e) => { if (this.tabs.some((t) => t.editor.dirty)) { e.preventDefault(); e.returnValue = ''; } };
    addEventListener('beforeunload', this.beforeUnload);
  }

  async load() {
    try {
      await this.loadTree();
    } catch (e) {
      toast(e.message, 'err');
      location.hash = '#/';
      return;
    }
    const b = app.bootBuild;
    if (b && b.project === this.key) {
      b.log.forEach((l) => this.appendLog(l.line, l.err));
      b.diagnostics.forEach((d) => this.addProblem(d));
      if (b.running) this.onBuildStart({}); else if (b.last) this.setStatus(b.last.ok ? 'ok' : 'err', b.last.ok ? 'Built' : 'Failed');
    }
    for (const t of app.bootTerminals || []) if (!t.exit && t.id) this.attachTerminal(t.id, t.title, false);
    app.bootTerminals = [];
    const restore = JSON.parse(sessionStorage.getItem(`nf-tabs:${this.key}`) || 'null');
    const files = this.entries.filter((e) => !e.dir).map((e) => e.path);
    const toOpen = restore?.tabs?.filter((p) => files.includes(p)) || [];
    if (!toOpen.length) {
      const main = files.find((p) => /(^|\/)main\.(c|cpp|cc|cxx)$/.test(p)) || files.find((p) => /\.(c|cpp)$/.test(p));
      if (main) toOpen.push(main);
    }
    for (const p of toOpen) await this.open(p, { focus: false });
    if (restore?.active && files.includes(restore.active)) this.activate(restore.active);
  }

  async loadTree() {
    const data = await api('/api/tree', { query: { project: this.key } });
    this.project = data.project;
    this.titleEl.textContent = this.project.name;
    this.titleEl.title = this.project.path;
    document.title = `${this.project.name} · NativeForge`;
    this.entries = data.entries;
    if (!this.expanded.size) for (const e of this.entries) if (e.dir && !e.path.includes('/')) this.expanded.add(e.path);
    this.renderTree();
  }

  treeChanged() { clearTimeout(this.treeTimer); this.treeTimer = setTimeout(() => this.loadTree().catch(() => {}), 300); }

  renderTree() {
    const rows = [];
    const children = new Map();
    for (const e of this.entries) {
      const parent = dirname(e.path);
      if (!children.has(parent)) children.set(parent, []);
      children.get(parent).push(e);
    }
    const walk = (dir, depth) => {
      for (const e of children.get(dir) || []) {
        const open = this.expanded.has(e.path);
        const errors = !e.dir && (this.diagnostics.get(e.path) || []).some((d) => d.severity === 'error');
        const row = h('div', {
          class: `node ${this.active?.path === e.path ? 'active' : ''}`,
          style: { paddingLeft: `${8 + depth * 14}px` },
          title: e.path,
          draggable: true,
          onclick: () => (e.dir ? this.toggle(e.path) : this.open(e.path)),
          oncontextmenu: (ev) => { ev.preventDefault(); this.entryMenu(ev, e); },
          ondragstart: (ev) => ev.dataTransfer.setData('text/x-nf-path', e.path),
        },
        h('span', { class: 'twisty' }, e.dir ? icon(open ? 'down' : 'right', 'i s') : null),
        icon(e.dir ? 'folder' : 'file', 'i s'),
        h('span', { class: 'label' }, basename(e.path)),
        errors ? h('span', { class: 'dot', style: { background: 'var(--error)' } }) : null);
        if (e.dir) {
          row.style.color = 'var(--text)';
          row.querySelectorAll('svg')[1].style.color = 'var(--warning)';
          this.dropTarget(row, e.path);
        } else row.querySelectorAll('svg')[0].style.color = 'var(--muted)';
        rows.push(row);
        if (e.dir && open) walk(e.path, depth + 1);
      }
    };
    walk('', 0);
    fill(this.tree, ...rows);
    this.dropTarget(this.tree, '');
    try { sessionStorage.setItem(`nf-exp:${this.key}`, JSON.stringify([...this.expanded])); } catch { /* ignore */ }
  }

  dropTarget(el, dir) {
    el.ondragover = (ev) => { ev.preventDefault(); ev.stopPropagation(); el.classList.add('drop'); };
    el.ondragleave = () => el.classList.remove('drop');
    el.ondrop = async (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      el.classList.remove('drop');
      const moved = ev.dataTransfer.getData('text/x-nf-path');
      if (moved) {
        const target = dir ? `${dir}/${basename(moved)}` : basename(moved);
        if (target !== moved && !target.startsWith(`${moved}/`)) await this.fsOp({ op: 'rename', path: moved, to: target }, moved, target);
      } else if (ev.dataTransfer.files.length) {
        await this.uploadFiles(dir, ev.dataTransfer.files);
      }
    };
  }

  toggle(path) {
    if (this.expanded.has(path)) this.expanded.delete(path); else this.expanded.add(path);
    this.renderTree();
  }

  entryMenu(ev, e) {
    const dir = e.dir ? e.path : dirname(e.path);
    menu(ev.clientX, ev.clientY, [
      ...(e.dir ? [] : [{ label: 'Open', icon: 'file', onClick: () => this.open(e.path) }]),
      { label: 'New file…', icon: 'newFile', onClick: () => this.newEntry(dir, false) },
      { label: 'New folder…', icon: 'newFolder', onClick: () => this.newEntry(dir, true) },
      { label: 'Upload here…', icon: 'upload', onClick: () => this.upload(dir) },
      'sep',
      { label: 'Rename…', icon: 'edit', onClick: () => this.rename(e) },
      ...(e.dir ? [] : [{ label: 'Download', icon: 'download', onClick: () => this.download(e.path) }]),
      { label: 'Delete', icon: 'delete', danger: true, onClick: () => this.remove(e) },
    ]);
  }

  async fsOp(body, oldPath, newPath) {
    try {
      await api('/api/fs', { method: 'POST', body: { project: this.key, ...body } });
      if (oldPath && newPath) {
        for (const t of this.tabs) {
          if (t.path === oldPath || t.path.startsWith(`${oldPath}/`)) {
            const p = newPath + t.path.slice(oldPath.length);
            t.editor.destroy();
            t.el.remove();
            this.tabs = this.tabs.filter((x) => x !== t);
            await this.open(p, { focus: false });
          }
        }
      }
      await this.loadTree();
    } catch (e) { toast(e.message, 'err'); }
  }

  async newEntry(dir, folder) {
    const name = await prompt(folder ? 'New folder' : 'New file', { label: dir ? `In ${dir}/` : 'In the project folder', placeholder: folder ? 'folder' : 'file.cpp', ok: 'Create' });
    if (!name?.trim()) return;
    const path = dir ? `${dir}/${name.trim()}` : name.trim();
    await this.fsOp({ op: folder ? 'mkdir' : 'touch', path });
    if (dir) this.expanded.add(dir);
    if (!folder) this.open(path);
  }

  async rename(e) {
    const name = basename(e.path);
    const dot = name.lastIndexOf('.');
    const value = await prompt('Rename', { value: name, ok: 'Rename', select: () => [0, dot > 0 && !e.dir ? dot : name.length] });
    if (!value?.trim() || value === name) return;
    const dir = dirname(e.path);
    const to = dir ? `${dir}/${value.trim()}` : value.trim();
    await this.fsOp({ op: 'rename', path: e.path, to }, e.path, to);
  }

  async remove(e) {
    if (!await confirm('Delete', `Delete ${e.path}${e.dir ? ' and everything in it' : ''} from the phone?`, 'Delete', true)) return;
    for (const t of [...this.tabs]) if (t.path === e.path || t.path.startsWith(`${e.path}/`)) this.closeTab(t, true);
    await this.fsOp({ op: 'delete', path: e.path });
  }

  upload(dir) {
    const input = h('input', { type: 'file', multiple: true, style: { display: 'none' } });
    input.onchange = () => { this.uploadFiles(dir, input.files); input.remove(); };
    document.body.append(input);
    input.click();
  }

  async uploadFiles(dir, files) {
    for (const f of files) {
      const path = dir ? `${dir}/${f.name}` : f.name;
      try {
        await api('/api/file', { method: 'PUT', query: { project: this.key, path }, raw: await f.arrayBuffer() });
      } catch (e) { toast(`${f.name}: ${e.message}`, 'err'); }
    }
    if (dir) this.expanded.add(dir);
    await this.loadTree();
    toast(`Uploaded ${files.length} file${files.length === 1 ? '' : 's'}`, 'ok');
  }

  download(path) {
    const url = new URL('/api/download', location.origin);
    url.searchParams.set('project', this.key);
    url.searchParams.set('path', path);
    h('a', { href: url.toString(), download: basename(path) }).click();
  }

  downloadArtifact() {
    const url = new URL('/api/artifact', location.origin);
    url.searchParams.set('project', this.key);
    h('a', { href: url.toString() }).click();
  }

  // ---- editor tabs
  async open(path, { focus = true } = {}) {
    const existing = this.tabs.find((t) => t.path === path);
    if (existing) { this.activate(path, focus); return existing; }
    let file;
    try { file = await api('/api/file', { query: { project: this.key, path } }); } catch (e) { toast(e.message, 'err'); return null; }
    if (file.binary) {
      if (await confirm('Binary file', `${basename(path)} is not a text file (${file.size} bytes). Download it?`, 'Download')) this.download(path);
      return null;
    }
    const host = h('div', { style: { position: 'absolute', inset: '0', display: 'none' } });
    this.editorArea.append(host);
    const tab = { path, host, modified: file.modified };
    tab.el = h('div', { class: 'tab', title: path, onclick: () => this.activate(path), onauxclick: (e) => { if (e.button === 1) this.closeTab(tab); } },
      icon('file', 'i s'), h('span', { class: 'label' }, basename(path)),
      h('button', { class: 'close', title: 'Close', onclick: (e) => { e.stopPropagation(); this.closeTab(tab); } }, icon('close', 'i s')));
    tab.editor = new FileEditor(host, file.text, {
      project: this.key, path, channel: app.channel, theme: app.theme,
      onChange: (dirty) => tab.el.classList.toggle('dirty', dirty),
      onSave: () => this.save(tab),
      onCursor: (c) => { if (this.active === tab) this.cursorEl.textContent = `Ln ${c.line}, Col ${c.col}`; },
      onDefinition: (loc) => this.goToDefinition(loc),
      commands: [{ key: 'Mod-p', preventDefault: true, run: () => { this.quickOpen(); return true; } }],
    });
    tab.editor.setDiagnostics(this.diagnostics.get(path));
    this.tabs.push(tab);
    this.tabBar.append(tab.el);
    this.activate(path, focus);
    return tab;
  }

  activate(path, focus = true) {
    const tab = this.tabs.find((t) => t.path === path);
    if (!tab) return;
    this.active = tab;
    for (const t of this.tabs) {
      t.host.style.display = t === tab ? 'block' : 'none';
      t.el.classList.toggle('active', t === tab);
    }
    this.placeholder.style.display = 'none';
    tab.el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    const c = tab.editor.cursor();
    this.cursorEl.textContent = `Ln ${c.line}, Col ${c.col}`;
    tab.editor.view.requestMeasure();
    if (focus) tab.editor.focus();
    this.renderTree();
    this.rememberTabs();
  }

  async closeTab(tab, force = false) {
    if (!force && tab.editor.dirty) {
      const choice = await dialog({
        title: 'Unsaved changes', body: h('p', null, `Save changes to ${basename(tab.path)}?`),
        actions: [{ label: 'Cancel', value: 'cancel' }, { label: "Don't save", value: 'discard' }, { label: 'Save', primary: true, value: 'save' }],
      });
      if (!choice || choice === 'cancel') return;
      if (choice === 'save' && !await this.save(tab)) return;
    }
    const index = this.tabs.indexOf(tab);
    tab.editor.destroy();
    tab.host.remove();
    tab.el.remove();
    this.tabs.splice(index, 1);
    if (this.active === tab) {
      this.active = null;
      const next = this.tabs[Math.min(index, this.tabs.length - 1)];
      if (next) this.activate(next.path); else { this.placeholder.style.display = ''; this.cursorEl.textContent = ''; this.renderTree(); }
    }
    this.rememberTabs();
  }

  rememberTabs() {
    try { sessionStorage.setItem(`nf-tabs:${this.key}`, JSON.stringify({ tabs: this.tabs.map((t) => t.path), active: this.active?.path })); } catch { /* ignore */ }
  }

  /** Saves one tab; saves of the same tab run one after another (each needs the previous mtime). */
  save(tab = this.active) {
    if (!tab) return Promise.resolve(true);
    tab.saving = (tab.saving || Promise.resolve(true)).then(() => this.saveNow(tab));
    return tab.saving;
  }

  async saveNow(tab) {
    if (!tab.editor.dirty && tab.modified !== undefined) return true;
    const text = tab.editor.text;
    try {
      const r = await api('/api/file', { method: 'PUT', query: { project: this.key, path: tab.path, expected: tab.modified }, raw: new TextEncoder().encode(text) });
      tab.modified = r.modified;
      tab.editor.markSaved(text);
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        const overwrite = await confirm('File changed on the phone', `${tab.path} was changed on the phone after you opened it. Overwrite it with your version?`, 'Overwrite', true);
        if (overwrite) { tab.modified = undefined; return this.saveNow(tab); }
        return false;
      }
      toast(`Could not save ${basename(tab.path)}: ${e.message}`, 'err');
      return false;
    }
  }

  async saveAll() {
    for (const t of this.tabs) if (t.editor.dirty && !await this.save(t)) return false;
    return true;
  }

  async goToDefinition(loc) {
    if (!loc) { toast('No definition found'); return; }
    if (!loc.inside) { toast(`Defined in ${loc.path}:${loc.range.start.line + 1}`); return; }
    const tab = await this.open(loc.path);
    tab?.editor.goToRange(loc.range);
  }

  quickOpen() {
    document.querySelector('.quick')?.remove();
    const files = this.entries.filter((e) => !e.dir).map((e) => e.path);
    const input = h('input', { class: 'field', placeholder: 'Go to file…', spellcheck: false });
    const items = h('div', { class: 'items' });
    let sel = 0;
    let shown = [];
    const score = (p, q) => {
      if (!q) return 1;
      const name = basename(p).toLowerCase();
      const lower = p.toLowerCase();
      if (name.startsWith(q)) return 3;
      if (name.includes(q)) return 2;
      let i = 0;
      for (const ch of lower) if (ch === q[i]) i++;
      return i === q.length ? 1 : 0;
    };
    const render = () => {
      const q = input.value.toLowerCase().trim();
      shown = files.map((p) => [p, score(p, q)]).filter(([, s]) => s > 0).sort((a, b) => b[1] - a[1] || a[0].length - b[0].length).slice(0, 50).map(([p]) => p);
      sel = Math.min(sel, Math.max(shown.length - 1, 0));
      fill(items, ...shown.map((p, i) => h('div', { class: `item ${i === sel ? 'sel' : ''}`, onmousedown: (e) => { e.preventDefault(); pick(p); } },
        h('span', null, basename(p)), h('span', { class: 'p' }, dirname(p)))));
    };
    const close = () => box.remove();
    const pick = (p) => { close(); if (p) this.open(p); };
    input.addEventListener('input', () => { sel = 0; render(); });
    input.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown') { sel = Math.min(sel + 1, shown.length - 1); render(); e.preventDefault(); }
      else if (e.key === 'ArrowUp') { sel = Math.max(sel - 1, 0); render(); e.preventDefault(); }
      else if (e.key === 'Enter') pick(shown[sel]);
      else if (e.key === 'Escape') close();
    });
    input.addEventListener('blur', () => setTimeout(close, 100));
    const box = h('div', { class: 'quick' }, input, items);
    document.body.append(box);
    render();
    input.focus();
  }

  setDiagnostics(path, items) {
    this.diagnostics.set(path, items);
    this.tabs.find((t) => t.path === path)?.editor.setDiagnostics(items);
    this.renderTree();
    this.renderProblems();
  }

  setIntel(status) {
    const dot = this.intelEl.querySelector('.dotc');
    dot.className = `dotc ${{ ready: 'ok', starting: 'busy', failed: 'err' }[status] || ''}`;
    this.intelEl.lastChild.textContent = status === 'off' ? 'clangd off' : status === 'starting' ? 'clangd starting…' : status === 'failed' ? 'clangd failed' : 'clangd';
  }

  resync() {
    for (const t of this.tabs) if (t.editor.clangd) t.editor.sync();
  }

  // ---- build and run
  async build(run) {
    if (!await this.saveAll()) return;
    this.clearLog();
    this.showPanel('output');
    // The program's off-screen display takes the editor area's shape, in device pixels.
    const rect = this.editorArea.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    app.channel.send({ t: 'build', project: this.key, run, on: this.runOn, w: Math.round(rect.width * dpr), h: Math.round(rect.height * dpr) });
  }

  chooseRunTarget(e) {
    const r = e.currentTarget.getBoundingClientRect();
    const pick = (target) => {
      this.runOn = target;
      try { localStorage.setItem('nf-run-on', target); } catch { /* storage blocked */ }
      this.updateRunOn();
    };
    menu(r.left, r.bottom + 4, [
      { label: 'Run graphical programs here, in the browser', icon: this.runOn === 'browser' ? 'check' : 'monitor', onClick: () => pick('browser') },
      { label: 'Run graphical programs on the phone\'s screen', icon: this.runOn === 'phone' ? 'check' : 'phone', onClick: () => pick('phone') },
    ]);
  }

  updateRunOn() {
    fill(this.runOnBtn, icon(this.runOn === 'phone' ? 'phone' : 'monitor'));
    this.runOnBtn.title = this.runOn === 'phone' ? 'Graphical programs run on the phone\'s screen (click to change)' : 'Graphical programs run in this browser (click to change)';
  }

  // ---- program screen
  openScreen(session) {
    if (this.screen && this.screen.id === session.id && !this.screen.ended) { this.screen.focus(); return; }
    this.closeScreen();
    this.screen = new ScreenView(session, {
      onStop: () => app.channel.send({ t: 'screen.stop' }),
      onClose: () => this.closeScreen(),
    });
    this.editorArea.append(this.screen.el);
    this.updateScreenItem();
    setTimeout(() => this.screen?.focus(), 0);
  }

  closeScreen() {
    this.screen?.dispose();
    this.screen = null;
    this.updateScreenItem();
  }

  updateScreenItem() {
    const hidden = app.screen && !this.screen;
    this.screenItem.style.display = hidden ? '' : 'none';
    if (hidden) this.screenItem.lastChild.textContent = `${app.screen.title} is running · show`;
  }

  onBuildStart() {
    this.buildProblems = [];
    this.renderProblems();
    this.setStatus('run', 'Building…');
    this.buildBtn.disabled = true;
    this.runBtn.disabled = true;
    this.stopBtn.style.display = '';
  }

  onBuildDone(m) {
    if (m.project !== this.key) return;
    this.setStatus(m.ok ? 'ok' : 'err', m.ok ? `Built in ${(m.millis / 1000).toFixed(1)}s` : 'Build failed');
    this.buildBtn.disabled = false;
    this.runBtn.disabled = false;
    this.stopBtn.style.display = 'none';
    if (!m.ok && this.buildProblems.some((p) => p.severity === 'error')) this.showPanel('problems');
  }

  setStatus(kind, text) { this.status.className = `chip ${kind}`; this.status.textContent = text; }

  clearLog() { fill(this.log, ); this.logLines = 0; }

  appendLog(line, err) {
    const stick = this.log.scrollTop + this.log.clientHeight >= this.log.scrollHeight - 4;
    const cls = err ? 'err' : /^\[\d+\/\d+\]/.test(line) ? 'step' : /^Build succeeded/.test(line) ? 'ok' : '';
    this.log.append(h('div', { class: cls }, line));
    this.logLines = (this.logLines || 0) + 1;
    if (this.logLines > 4000) { this.log.firstChild?.remove(); this.logLines--; }
    if (stick) this.log.scrollTop = this.log.scrollHeight;
  }

  addProblem(d) { this.buildProblems.push(d); this.renderProblems(); }

  renderProblems() {
    const live = [...this.diagnostics.entries()].flatMap(([path, items]) => items.map((d) => ({
      severity: d.severity === 'hint' || d.severity === 'info' ? 'note' : d.severity, message: d.message, path, line: d.range.start.line + 1, column: d.range.start.ch + 1, live: true,
    })));
    const seen = new Set(live.map((p) => `${p.path}:${p.line}:${p.message}`));
    const all = [...this.buildProblems.filter((p) => !seen.has(`${p.path}:${p.line}:${p.message}`)), ...live];
    const errors = all.filter((p) => p.severity === 'error').length;
    const warnings = all.filter((p) => p.severity === 'warning').length;
    this.problemCount.childNodes[1].textContent = String(errors);
    this.problemCount.childNodes[3].textContent = String(warnings);
    this.problemTotal = all.length;
    fill(this.problemsEl, ...(all.length ? all.map((p) => h('div', {
      class: `problem ${p.severity}`,
      onclick: async () => { if (!p.path) return; const tab = await this.open(p.path); tab?.editor.goTo(p.line || 1, p.column || 1); },
    }, icon(p.severity === 'error' ? 'error' : p.severity === 'warning' ? 'warning' : 'info', 'i s'),
    h('span', null, p.message), p.path ? h('span', { class: 'where' }, `${p.path}${p.line ? `:${p.line}` : ''}`) : null))
      : [h('div', { class: 'empty' }, 'No problems')]));
    this.renderPanelTabs();
  }

  // ---- panel and terminals
  renderPanelTabs() {
    const tab = (id, label, extra) => h('button', { class: `ptab ${this.panelTab === id ? 'active' : ''}`, onclick: () => this.showPanel(id) }, label, extra);
    fill(this.panelTabs, 
      tab('output', 'Output'),
      tab('problems', 'Problems', this.problemTotal ? h('span', { class: 'count' }, String(this.problemTotal)) : null),
      ...this.terminals.map((t) => tab(t.id, h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: '4px' } }, icon('terminal', 'i s'), t.title),
        h('span', { class: 'x', title: 'Close terminal', onclick: (e) => { e.stopPropagation(); this.closeTerminal(t); } }, icon('close', 'i s')))),
      iconButton('add', 'New terminal', () => this.newTerminal()),
      h('div', { class: 'spacer' }),
      this.panelTab === 'output' ? iconButton('delete', 'Clear output', () => this.clearLog()) : null,
      iconButton('down', 'Hide panel', () => this.panel.classList.add('hidden')));
    this.log.style.display = this.panelTab === 'output' ? '' : 'none';
    this.problemsEl.style.display = this.panelTab === 'problems' ? '' : 'none';
    for (const t of this.terminals) t.host.style.display = this.panelTab === t.id ? '' : 'none';
  }

  showPanel(id) {
    this.panel.classList.remove('hidden');
    this.panelTab = id;
    this.renderPanelTabs();
    const t = this.terminals.find((x) => x.id === id);
    if (t) { t.view.refit(); t.view.focus(); }
  }

  async newTerminal() {
    try {
      const { id, title } = await api('/api/terminals', { method: 'POST', body: { project: this.key, rows: 24, cols: 100 } });
      this.attachTerminal(id, title, true);
    } catch (e) { toast(e.message, 'err'); }
  }

  attachTerminal(id, title, show) {
    if (this.terminals.some((t) => t.id === id)) { if (show) this.showPanel(id); return; }
    const host = h('div', { class: 'term-host', style: { display: 'none' } });
    this.panelBody.append(host);
    const t = { id, title, host };
    this.terminals.push(t);
    if (show) { this.panelTab = id; this.panel.classList.remove('hidden'); }
    this.renderPanelTabs();
    t.view = new TerminalView(host, id, app.theme);
    if (show) setTimeout(() => { t.view.refit(); t.view.focus(); }, 0);
  }

  closeTerminal(t) {
    t.view.dispose();
    t.host.remove();
    this.terminals = this.terminals.filter((x) => x !== t);
    api('/api/terminals/close', { method: 'POST', body: { id: t.id } }).catch(() => {});
    if (this.panelTab === t.id) this.panelTab = 'output';
    this.renderPanelTabs();
  }

  // ---- keys and lifecycle
  onKey(e) {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.shiftKey && (e.key === 'B' || e.key === 'b')) { e.preventDefault(); this.build(false); }
    else if (e.key === 'F5') { e.preventDefault(); this.build(true); }
    else if (mod && !e.shiftKey && e.key === 's') { e.preventDefault(); this.save(); }
    else if (mod && e.key === 'p') { e.preventDefault(); this.quickOpen(); }
    else if (mod && e.shiftKey && e.code === 'Backquote') { e.preventDefault(); this.newTerminal(); }
    else if (mod && e.code === 'Backquote') { e.preventDefault(); this.panel.classList.toggle('hidden'); }
    else if (e.altKey && e.key === 'w' && this.active) { e.preventDefault(); this.closeTab(this.active); }
  }

  setTheme(theme) {
    for (const t of this.tabs) t.editor.setTheme(theme);
    for (const t of this.terminals) t.view.setTheme(theme);
  }

  dispose() {
    this.offs.forEach((off) => off());
    removeEventListener('keydown', this.keys);
    removeEventListener('beforeunload', this.beforeUnload);
    for (const t of this.tabs) t.editor.destroy();
    for (const t of this.terminals) t.view.dispose(); // the shells keep running on the phone
    this.screen?.dispose(); // so does the program
  }
}
