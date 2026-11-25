// NativeForge in the browser: pairing, projects, and the workspace (explorer, editor,
// build output, problems, terminals). Everything runs on the phone; this page is the UI.
import { api, ApiError, Channel, setUnauthorizedHandler } from './api.js';
import { h, fill, icon, iconButton, toast, dialog, prompt, confirm, menu, resizable, basename, dirname, copyText, keyHint } from './util.js';
import { applyPalette } from './theme.js';
import { effectiveTheme, onPrefsChange, openSettings, bumpEditorFont } from './settings.js';
import { FileEditor } from './editor.js';
import { TerminalView } from './terminal.js';
import { ScreenView } from './screen.js';

const root = document.getElementById('app');
// theme: the phone's theme (app.phoneTheme) with this browser's settings on top (settings.js).
const app = { theme: null, phoneTheme: null, projects: [], templates: [], channel: null, workspace: null, device: '' };

setUnauthorizedHandler(() => { if (app.channel) { app.channel.close(); app.channel = null; } showPairing(); });

function applyTheme() {
  if (!app.phoneTheme) return;
  app.theme = effectiveTheme(app.phoneTheme);
  applyPalette(app.theme);
  app.workspace?.setTheme(app.theme);
}
onPrefsChange(applyTheme);

const showSettings = () => openSettings(() => app.phoneTheme);
addEventListener('keydown', (e) => {
  if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && e.key === ',' && app.phoneTheme && !document.querySelector('.scrim')) {
    e.preventDefault();
    showSettings();
  }
});

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
  app.phoneTheme = data.theme;
  app.projects = data.projects;
  app.templates = data.templates;
  app.device = data.device;
  app.bootBuild = data.build;
  app.bootTerminals = data.terminals;
  app.intel = data.intel;
  app.screen = data.screen; // the program shown in the browser, if one runs
  applyTheme();
  document.title = `NativeForge · ${app.device}`;
  app.channel?.close();
  const channel = new Channel();
  app.channel = channel;
  channel.on('theme', (m) => { app.phoneTheme = m.theme; applyTheme(); });
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

/** The phone's web server takes request bodies up to 32 MB (HttpServer.maxBodyBytes). */
const MAX_UPLOAD_BYTES = 32 * 1024 * 1024;
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
      iconButton('settings', `Settings (${keyHint('Ctrl+,')})`, showSettings),
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
  const actions = (x, y) => menu(x, y, [
    { label: 'Open', icon: 'folder', onClick: () => openProject(p.key) },
    { label: 'Open in a terminal', icon: 'terminal', onClick: () => openStandaloneTerminal(p) },
    { label: 'Copy path on the phone', icon: 'copy', onClick: () => copyText(p.path, 'Path copied') },
    'sep',
    p.external
      ? { label: 'Remove from list', icon: 'close', onClick: () => removeProject(p) }
      : { label: 'Delete', icon: 'delete', danger: true, onClick: () => removeProject(p) },
  ]);
  const more = iconButton('more', 'Project actions', (e) => {
    e.stopPropagation();
    const r = e.currentTarget.getBoundingClientRect();
    actions(r.left, r.bottom);
  });
  return h('div', {
    class: 'card', onclick: () => openProject(p.key), title: p.path,
    oncontextmenu: (e) => { if (e.shiftKey) return; e.preventDefault(); actions(e.clientX, e.clientY); },
  },
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

/** A terminal in a dialog: the phone's home folder, or [project]'s folder. */
async function openStandaloneTerminal(project) {
  const view = h('div', { class: 'term-host', style: { position: 'absolute', inset: '0' } });
  let term;
  try {
    const { id } = await api('/api/terminals', { method: 'POST', body: { rows: 30, cols: 100, project: project?.key } });
    await dialog({
      title: project ? `Terminal · ${project.name}` : 'Terminal',
      wide: true,
      body: () => { const box = h('div', { style: { position: 'relative', height: 'calc(60vh / var(--zoom, 1))' } }, view); setTimeout(() => { term = new TerminalView(view, id, app.theme); term.focus(); }, 0); return box; },
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
    this.dropHint = h('div', { class: 'drop-hint', style: { display: 'none' } });
    this.explorer = h('div', { class: 'explorer' },
      h('div', { class: 'head' }, h('span', null, 'Explorer'), h('div', { class: 'spacer' }),
        iconButton('newFile', 'New file', () => this.newEntry('', false)),
        iconButton('newFolder', 'New folder', () => this.newEntry('', true)),
        iconButton('upload', 'Upload files (or drop files and folders on a folder)', () => this.upload('')),
        iconButton('collapse', 'Collapse folders', () => this.collapseAll()),
        iconButton('refresh', 'Refresh', () => this.loadTree())),
      this.dropHint,
      this.tree);
    // Files dropped anywhere else would make the browser open them in place of the IDE.
    const outside = (ev) => {
      if (!ev.dataTransfer?.types.includes('Files')) return;
      ev.preventDefault();
      ev.dataTransfer.dropEffect = 'none';
      if (this.dropDir !== undefined) this.endDrag();
      if (ev.type === 'drop') toast('Drop files on a folder in the explorer to upload them there');
    };
    // dragend: the drag ended without a drop.
    this.windowListeners = [['dragover', outside], ['drop', outside], ['dragend', () => this.endDrag()]];
    for (const [type, fn] of this.windowListeners) window.addEventListener(type, fn);
    this.explorer.addEventListener('dragleave', (ev) => { if (!this.explorer.contains(ev.relatedTarget)) this.endDrag(); });
    this.tree.addEventListener('contextmenu', (ev) => {
      if (ev.shiftKey || ev.target.closest('.node')) return;
      ev.preventDefault();
      this.folderMenu(ev.clientX, ev.clientY);
    });
    this.tabBar = h('div', { class: 'tabs' });
    this.editorArea = h('div', { class: 'editor-area' });
    this.placeholder = h('div', { class: 'placeholder' }, icon('code'), h('div', null, 'Open a file from the explorer'),
      h('div', null, h('span', { class: 'kbd' }, 'Ctrl+P'), ' quick open · ', h('span', { class: 'kbd' }, 'F5'), ' build and run'));
    this.editorArea.append(this.placeholder);
    this.panelTabs = h('div', { class: 'panel-tabs' });
    this.panelBody = h('div', { class: 'panel-body' });
    this.log = h('div', { class: 'log' });
    this.problemsEl = h('div', { class: 'problems' });
    this.log.addEventListener('contextmenu', (ev) => { if (!ev.shiftKey) { ev.preventDefault(); this.logMenu(ev); } });
    this.problemsEl.addEventListener('contextmenu', (ev) => { if (!ev.shiftKey) { ev.preventDefault(); this.problemsMenu(ev); } });
    this.panelBody.append(this.log, this.problemsEl);
    this.panel = h('div', { class: 'panel' }, this.panelTabs, this.panelBody);
    const splitV = h('div', { class: 'splitter-v' });
    const splitH = h('div', { class: 'splitter-h' });
    this.cursorEl = h('span', { class: 'item' }, '');
    this.screenItem = h('span', { class: 'item click', style: { display: 'none' }, title: 'Show the running program', onclick: () => app.screen && this.openScreen(app.screen) }, icon('game', 'i s'), '');
    this.intelEl = h('span', { class: 'item', title: 'clangd: completion, live errors, go to definition' }, h('span', { class: 'dotc' }), 'clangd');
    this.problemCount = h('span', { class: 'item click', onclick: () => this.showPanel('problems') }, icon('error', 'i s'), '0', icon('warning', 'i s'), '0');
    this.prefsEl = h('span', { class: 'item click', title: 'Editor settings', onclick: showSettings });
    this.el = h('div', { class: 'workspace' },
      h('div', { class: 'topbar' },
        iconButton('back', 'Projects', async () => { if (await this.saveAll()) location.hash = '#/'; }),
        this.titleEl, this.status, h('div', { class: 'spacer' }),
        iconButton('save', 'Save (Ctrl+S)', () => this.save()),
        this.buildBtn, this.runBtn, this.runOnBtn, this.stopBtn,
        iconButton('download', 'Download the built program', () => this.downloadArtifact()),
        iconButton('terminal', 'New terminal (Ctrl+Shift+`)', () => this.newTerminal()),
        iconButton('panel', 'Toggle panel (Ctrl+`)', () => this.panel.classList.toggle('hidden')),
        iconButton('settings', `Settings (${keyHint('Ctrl+,')})`, showSettings)),
      h('div', { class: 'ws-body' }, this.explorer, splitV,
        h('div', { class: 'center' }, this.tabBar, this.editorArea, splitH, this.panel)),
      h('div', { class: 'statusbar' }, h('span', { class: 'item' }, icon('phone', 'i s'), app.device), this.problemCount, this.intelEl, this.screenItem, h('div', { class: 'spacer' }), this.cursorEl, this.prefsEl));
    this.updatePrefsItem();
    fill(root, this.el);
    // Ctrl+scroll (and pinch on a touchpad) changes the editor font, as in desktop editors.
    let wheel = 0;
    this.editorArea.addEventListener('wheel', (ev) => {
      if (!(ev.ctrlKey || ev.metaKey) || this.screen) return;
      ev.preventDefault();
      wheel += ev.deltaY;
      if (Math.abs(wheel) >= 40) { bumpEditorFont(app.theme, wheel < 0 ? 1 : -1); wheel = 0; }
    }, { passive: false });
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
        } else {
          row.querySelectorAll('svg')[0].style.color = 'var(--muted)';
          this.dropTarget(row, dirname(e.path)); // into the file's folder
        }
        rows.push(row);
        if (e.dir && open) walk(e.path, depth + 1);
      }
    };
    walk('', 0);
    fill(this.tree, ...rows);
    this.dropTarget(this.tree, '');
    try { sessionStorage.setItem(`nf-exp:${this.key}`, JSON.stringify([...this.expanded])); } catch { /* ignore */ }
  }

  /**
   * Makes [el] take drops into folder [dir]: entries dragged inside the explorer move there,
   * files and folders dragged from the computer are uploaded there. A closed folder opens
   * when something hovers over it for a moment.
   */
  dropTarget(el, dir) {
    const highlight = () => {
      this.tree.querySelectorAll('.drop').forEach((x) => x.classList.remove('drop'));
      this.tree.classList.remove('drop');
      // Light up the folder's row (a file row stands for its folder), or the whole tree for the top.
      // Looked up each time: opening a folder renders new rows.
      const row = dir ? [...this.tree.children].find((x) => x.title === dir) : null;
      (row || this.tree).classList.add('drop');
      this.dropHint.textContent = `Drop into ${dir ? `${dir}/` : 'the project folder'}`;
      this.dropHint.style.display = '';
    };
    el.ondragover = (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      ev.dataTransfer.dropEffect = ev.dataTransfer.types.includes('Files') ? 'copy' : 'move';
      if (this.dropDir !== dir) {
        this.dropDir = dir;
        highlight();
        clearTimeout(this.expandTimer);
        if (dir && !this.expanded.has(dir)) this.expandTimer = setTimeout(() => { this.expanded.add(dir); this.renderTree(); highlight(); }, 700);
      }
    };
    el.ondrop = async (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      this.endDrag();
      const moved = ev.dataTransfer.getData('text/x-nf-path');
      if (moved) {
        const target = dir ? `${dir}/${basename(moved)}` : basename(moved);
        if (target !== moved && !target.startsWith(`${moved}/`)) await this.fsOp({ op: 'rename', path: moved, to: target }, moved, target);
      } else if (ev.dataTransfer.types.includes('Files')) {
        await this.uploadDropped(dir, ev.dataTransfer);
      }
    };
  }

  endDrag() {
    clearTimeout(this.expandTimer);
    this.dropDir = undefined;
    this.tree.querySelectorAll('.drop').forEach((x) => x.classList.remove('drop'));
    this.tree.classList.remove('drop');
    this.dropHint.style.display = 'none';
  }

  /** Files and whole folders dropped from the computer, uploaded under [dir] with their folder structure. */
  async uploadDropped(dir, dataTransfer) {
    // Entries must be taken before the first await: the browser empties dataTransfer after the event.
    const entries = [...dataTransfer.items || []].map((i) => i.webkitGetAsEntry?.()).filter(Boolean);
    let files;
    if (entries.length) {
      files = [];
      const readAll = (reader) => new Promise((resolve, reject) => {
        const all = [];
        const next = () => reader.readEntries((batch) => { if (batch.length) { all.push(...batch); next(); } else resolve(all); }, reject);
        next();
      });
      const walk = async (entry, prefix) => {
        if (entry.isFile) {
          files.push({ path: prefix + entry.name, file: await new Promise((resolve, reject) => entry.file(resolve, reject)) });
        } else if (entry.isDirectory) {
          for (const child of await readAll(entry.createReader())) await walk(child, `${prefix}${entry.name}/`);
        }
      };
      try {
        for (const entry of entries) await walk(entry, '');
      } catch (e) { toast(`Could not read the dropped files: ${e.message}`, 'err'); return; }
    } else {
      files = [...dataTransfer.files].map((file) => ({ path: file.name, file }));
    }
    await this.uploadFiles(dir, files);
  }

  /** [files]: File objects, or { path, file } with a path relative to [dir]. */
  async uploadFiles(dir, files) {
    const list = [...files].map((f) => (f instanceof File ? { path: f.name, file: f } : f));
    if (!list.length) { toast('Nothing to upload (empty folder?)'); return; }
    const target = (p) => (dir ? `${dir}/${p}` : p);
    const existing = new Set(this.entries.filter((e) => !e.dir).map((e) => e.path));
    const replaced = list.filter((f) => existing.has(target(f.path))).length;
    const where = dir ? `${dir}/` : 'the project folder';
    if (replaced && !await confirm('Replace files', `${replaced} of these file${replaced === 1 ? ' is' : 's are'} already in ${where}. Replace ${replaced === 1 ? 'it' : 'them'}?`, 'Replace', true)) return;
    const tooBig = list.filter((f) => f.file.size > MAX_UPLOAD_BYTES);
    const todo = list.filter((f) => f.file.size <= MAX_UPLOAD_BYTES);
    if (todo.length > 3) toast(`Uploading ${todo.length} files to ${where}…`);
    let done = 0;
    for (const f of todo) {
      try {
        await api('/api/file', { method: 'PUT', query: { project: this.key, path: target(f.path) }, raw: await f.file.arrayBuffer() });
        done++;
      } catch (e) { toast(`${f.path}: ${e.message}`, 'err'); }
    }
    if (dir) this.expanded.add(dir);
    for (const f of todo) { // open the folders that received files
      const parts = target(f.path).split('/');
      for (let i = 1; i < parts.length; i++) this.expanded.add(parts.slice(0, i).join('/'));
    }
    await this.loadTree();
    if (tooBig.length) toast(`Not uploaded (over ${MAX_UPLOAD_BYTES / 1024 / 1024} MB): ${tooBig.map((f) => f.path).join(', ')}`, 'err');
    if (done) toast(`Uploaded ${done} file${done === 1 ? '' : 's'} to ${where}`, 'ok');
  }

  toggle(path) {
    if (this.expanded.has(path)) this.expanded.delete(path); else this.expanded.add(path);
    this.renderTree();
  }

  entryMenu(ev, e) {
    if (e.dir) { this.folderMenu(ev.clientX, ev.clientY, e); return; }
    menu(ev.clientX, ev.clientY, [
      { label: 'Open', icon: 'file', onClick: () => this.open(e.path) },
      'sep',
      { label: 'New file…', icon: 'newFile', onClick: () => this.newEntry(dirname(e.path), false) },
      { label: 'New folder…', icon: 'newFolder', onClick: () => this.newEntry(dirname(e.path), true) },
      'sep',
      ...this.pathItems(e.path),
      'sep',
      { label: 'Rename…', icon: 'edit', onClick: () => this.rename(e) },
      { label: 'Duplicate…', icon: 'copy', onClick: () => this.duplicate(e) },
      { label: 'Download', icon: 'download', onClick: () => this.download(e.path) },
      { label: 'Delete', icon: 'delete', danger: true, onClick: () => this.remove(e) },
    ]);
  }

  /** The menu of a folder row, or of the empty part of the tree (the project folder) when [e] is missing. */
  folderMenu(x, y, e) {
    const dir = e ? e.path : '';
    menu(x, y, [
      e ? { label: this.expanded.has(dir) ? 'Collapse' : 'Expand', icon: this.expanded.has(dir) ? 'down' : 'right', onClick: () => this.toggle(dir) } : null,
      { label: 'New file…', icon: 'newFile', onClick: () => this.newEntry(dir, false) },
      { label: 'New folder…', icon: 'newFolder', onClick: () => this.newEntry(dir, true) },
      { label: 'Upload here…', icon: 'upload', onClick: () => this.upload(dir) },
      { label: 'Open in terminal', icon: 'terminal', onClick: () => this.newTerminal(dir) },
      'sep',
      ...(e ? this.pathItems(dir) : [{ label: 'Copy path on the phone', icon: 'copy', onClick: () => copyText(this.project.path, 'Path copied') }]),
      'sep',
      e ? { label: 'Rename…', icon: 'edit', onClick: () => this.rename(e) } : null,
      e ? { label: 'Duplicate…', icon: 'copy', onClick: () => this.duplicate(e) } : null,
      e ? { label: 'Delete', icon: 'delete', danger: true, onClick: () => this.remove(e) } : null,
      e ? null : { label: 'Collapse folders', icon: 'collapse', onClick: () => this.collapseAll() },
      e ? null : { label: 'Refresh', icon: 'refresh', onClick: () => this.loadTree() },
    ]);
  }

  pathItems(path) {
    return [
      { label: 'Copy path', icon: 'copy', onClick: () => copyText(path, 'Path copied') },
      { label: 'Copy path on the phone', icon: 'copy', onClick: () => copyText(`${this.project.path}/${path}`, 'Path copied') },
    ];
  }

  collapseAll() {
    this.expanded.clear();
    this.expanded.add('\u0000'); // not empty: an empty set opens the top folders again
    this.renderTree();
  }

  /** Opens the folders above [path] and scrolls its row into view. */
  reveal(path) {
    const parts = path.split('/');
    for (let i = 1; i < parts.length; i++) this.expanded.add(parts.slice(0, i).join('/'));
    this.renderTree();
    const row = [...this.tree.children].find((x) => x.title === path);
    if (row) { row.scrollIntoView({ block: 'nearest' }); row.classList.add('flash'); setTimeout(() => row.classList.remove('flash'), 900); }
  }

  async duplicate(e) {
    const name = basename(e.path);
    const dot = e.dir ? -1 : name.lastIndexOf('.');
    const stem = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : '';
    const dir = dirname(e.path);
    const join = (n) => (dir ? `${dir}/${n}` : n);
    const taken = new Set(this.entries.map((x) => x.path));
    let suggestion = `${stem}_copy${ext}`;
    for (let n = 2; taken.has(join(suggestion)); n++) suggestion = `${stem}_copy${n}${ext}`;
    const value = await prompt(e.dir ? 'Duplicate folder' : 'Duplicate file', {
      label: `Copy of ${e.path}`, value: suggestion, ok: 'Duplicate', select: () => [0, suggestion.length - ext.length],
    });
    if (!value?.trim()) return;
    const to = join(value.trim());
    await this.fsOp({ op: 'copy', path: e.path, to });
    if (!e.dir && this.entries.some((x) => x.path === to)) this.open(to);
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
    tab.el = h('div', {
      class: 'tab', title: path, onclick: () => this.activate(path), onauxclick: (e) => { if (e.button === 1) this.closeTab(tab); },
      oncontextmenu: (e) => { if (e.shiftKey) return; e.preventDefault(); this.tabMenu(e, tab); },
    },
      icon('file', 'i s'), h('span', { class: 'label' }, basename(path)),
      h('button', { class: 'close', title: 'Close', onclick: (e) => { e.stopPropagation(); this.closeTab(tab); } }, icon('close', 'i s')));
    tab.editor = new FileEditor(host, file.text, {
      project: this.key, path, channel: app.channel, theme: app.theme,
      onChange: (dirty) => {
        tab.el.classList.toggle('dirty', dirty);
        clearTimeout(tab.autoSave); // Settings › Auto save: a second after the last change
        if (dirty && app.theme.autoSave) tab.autoSave = setTimeout(() => { if (this.tabs.includes(tab)) this.save(tab, { auto: true }); }, 1000);
      },
      onSave: () => this.save(tab),
      onCursor: (c) => { if (this.active === tab) this.cursorEl.textContent = `Ln ${c.line}, Col ${c.col}`; },
      onDefinition: (loc) => this.goToDefinition(loc),
      commands: [
        { key: 'Mod-p', preventDefault: true, run: () => { this.quickOpen(); return true; } },
        { key: 'Mod-=', run: () => { bumpEditorFont(app.theme, 1); return true; } },
        { key: 'Mod-+', run: () => { bumpEditorFont(app.theme, 1); return true; } },
        { key: 'Mod--', run: () => { bumpEditorFont(app.theme, -1); return true; } },
        { key: 'Mod-0', run: () => { bumpEditorFont(app.theme, 0); return true; } },
      ],
      menuItems: () => [
        { label: 'Reveal in explorer', icon: 'folder', onClick: () => this.reveal(path) },
        { label: 'Copy path', icon: 'copy', onClick: () => copyText(path, 'Path copied') },
      ],
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

  tabMenu(e, tab) {
    const index = this.tabs.indexOf(tab);
    const others = this.tabs.filter((t) => t !== tab);
    const right = this.tabs.slice(index + 1);
    const saved = this.tabs.filter((t) => !t.editor.dirty);
    menu(e.clientX, e.clientY, [
      { label: 'Close', icon: 'close', hint: 'Alt+W', onClick: () => this.closeTab(tab) },
      { label: 'Close others', disabled: !others.length, onClick: () => this.closeTabs(others) },
      { label: 'Close to the right', disabled: !right.length, onClick: () => this.closeTabs(right) },
      { label: 'Close saved', disabled: !saved.length, onClick: () => this.closeTabs(saved) },
      { label: 'Close all', onClick: () => this.closeTabs([...this.tabs]) },
      'sep',
      { label: 'Save', icon: 'save', hint: 'Ctrl+S', disabled: !tab.editor.dirty, onClick: () => this.save(tab) },
      { label: 'Reveal in explorer', icon: 'folder', onClick: () => this.reveal(tab.path) },
      ...this.pathItems(tab.path),
      { label: 'Download', icon: 'download', onClick: () => this.download(tab.path) },
    ]);
  }

  /** Closes [tabs] one by one; stops at the first whose unsaved changes the user keeps (Cancel). */
  async closeTabs(tabs) {
    for (const t of tabs) {
      if (!this.tabs.includes(t)) continue;
      await this.closeTab(t);
      if (this.tabs.includes(t)) return;
    }
  }

  rememberTabs() {
    try { sessionStorage.setItem(`nf-tabs:${this.key}`, JSON.stringify({ tabs: this.tabs.map((t) => t.path), active: this.active?.path })); } catch { /* ignore */ }
  }

  /** Saves one tab; saves of the same tab run one after another (each needs the previous mtime). */
  save(tab = this.active, opts = {}) {
    if (!tab) return Promise.resolve(true);
    tab.saving = (tab.saving || Promise.resolve(true)).then(() => this.saveNow(tab, opts));
    return tab.saving;
  }

  async saveNow(tab, { auto = false } = {}) {
    if (!tab.editor.dirty && tab.modified !== undefined) return true;
    // Format on save, but not on auto save: the text would move while you type.
    if (!auto && app.theme.formatOnSave && tab.editor.clangd) await tab.editor.format(4000);
    const text = tab.editor.text;
    try {
      const r = await api('/api/file', { method: 'PUT', query: { project: this.key, path: tab.path, expected: tab.modified }, raw: new TextEncoder().encode(text) });
      tab.modified = r.modified;
      tab.editor.markSaved(text);
      return true;
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        if (auto) { toast(`${tab.path} changed on the phone: auto save paused until you save it (${keyHint('Ctrl+S')})`, 'err'); return false; }
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

  logMenu(ev) {
    const selection = String(getSelection() || '');
    menu(ev.clientX, ev.clientY, [
      { label: 'Copy', icon: 'copy', hint: 'Ctrl+C', disabled: !selection || !this.log.contains(getSelection().anchorNode), onClick: () => copyText(selection, null) },
      { label: 'Copy all output', icon: 'copy', disabled: !this.log.childElementCount, onClick: () => copyText(this.log.innerText, 'Output copied') },
      'sep',
      { label: 'Clear output', icon: 'delete', onClick: () => this.clearLog() },
    ]);
  }

  problemsMenu(ev) {
    const row = ev.target.closest('.problem');
    const p = row && this.problemList?.[[...this.problemsEl.children].indexOf(row)];
    const line = (x) => `${x.path ? `${x.path}${x.line ? `:${x.line}${x.column ? `:${x.column}` : ''}` : ''}: ` : ''}${x.severity}: ${x.message}`;
    menu(ev.clientX, ev.clientY, [
      p?.path ? { label: 'Go to problem', icon: 'jump', onClick: () => row.click() } : null,
      p ? { label: 'Copy', icon: 'copy', onClick: () => copyText(line(p), 'Problem copied') } : null,
      p ? { label: 'Copy message', icon: 'copy', onClick: () => copyText(p.message, 'Message copied') } : null,
      'sep',
      { label: 'Copy all problems', icon: 'copy', disabled: !this.problemList?.length, onClick: () => copyText(this.problemList.map(line).join('\n'), 'Problems copied') },
    ]);
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
    this.problemList = all;
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
      ...this.terminals.map((t) => {
        const el = tab(t.id, h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: '4px' } }, icon('terminal', 'i s'), t.title),
          h('span', { class: 'x', title: 'Close terminal', onclick: (e) => { e.stopPropagation(); this.closeTerminal(t); } }, icon('close', 'i s')));
        el.addEventListener('contextmenu', (e) => {
          if (e.shiftKey) return;
          e.preventDefault();
          menu(e.clientX, e.clientY, [
            { label: 'New terminal', icon: 'add', onClick: () => this.newTerminal() },
            'sep',
            { label: 'Close terminal', icon: 'close', onClick: () => this.closeTerminal(t) },
            { label: 'Close other terminals', disabled: this.terminals.length < 2, onClick: () => this.terminals.filter((x) => x !== t).forEach((x) => this.closeTerminal(x)) },
          ]);
        });
        return el;
      }),
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

  /** A terminal in the project folder, or in its folder [dir]. */
  async newTerminal(dir) {
    try {
      const { id, title } = await api('/api/terminals', { method: 'POST', body: { project: this.key, dir: dir || undefined, rows: 24, cols: 100 } });
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
    t.view = new TerminalView(host, id, app.theme, {
      menuItems: () => [
        { label: 'New terminal', icon: 'add', onClick: () => this.newTerminal() },
        { label: 'Close terminal', icon: 'close', onClick: () => this.closeTerminal(t) },
      ],
    });
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
    this.updatePrefsItem();
  }

  updatePrefsItem() {
    const t = app.theme;
    fill(this.prefsEl, icon('settings', 'i s'), `${Math.round(t.fontSize || 13)} px · Spaces: ${t.tabWidth || 4}${t.uiScale && t.uiScale !== 100 ? ` · ${t.uiScale}%` : ''}`);
  }

  dispose() {
    this.offs.forEach((off) => off());
    removeEventListener('keydown', this.keys);
    removeEventListener('beforeunload', this.beforeUnload);
    for (const [type, fn] of this.windowListeners || []) window.removeEventListener(type, fn);
    for (const t of this.tabs) t.editor.destroy();
    for (const t of this.terminals) t.view.dispose(); // the shells keep running on the phone
    this.screen?.dispose(); // so does the program
  }
}
