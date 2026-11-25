// Settings of this browser: interface size, editor font and behaviour, terminal font.
// They start from the phone's settings (sent with the theme) and only what is changed here
// is kept, in this browser's storage: the phone and other browsers are not affected.
import { h, fill, icon, dialog, keyHint } from './util.js';

const KEY = 'nf-prefs';

/** Values the phone sends with its theme; a browser change overrides them here only. */
const FROM_PHONE = ['fontSize', 'tabWidth', 'wordWrap', 'lineNumbers', 'highlightLine', 'terminalFontSize', 'formatOnSave'];

/** Settings only the browser has, with their defaults. */
const BROWSER_DEFAULTS = { uiScale: 100, fontFamily: '', lineHeight: 1.5, ligatures: true, autoSave: false };

export const EDITOR_FONT_SIZES = { min: 8, max: 40 };
export const TERMINAL_FONT_SIZES = { min: 8, max: 32 };
const UI_SCALES = [75, 80, 90, 100, 110, 125, 150, 175, 200];
const LINE_HEIGHTS = [1.2, 1.35, 1.5, 1.65, 1.8, 2];
const TAB_WIDTHS = [2, 4, 8];
/** Common programming fonts; only the ones installed on this computer show up as they are. */
const FONTS = ['JetBrains Mono', 'Fira Code', 'Cascadia Code', 'Source Code Pro', 'Hack', 'IBM Plex Mono', 'Ubuntu Mono',
  'DejaVu Sans Mono', 'Roboto Mono', 'Noto Sans Mono', 'Consolas', 'Menlo', 'Monaco', 'SF Mono', 'Courier New'];

let prefs = load();
const listeners = new Set();

function load() {
  try {
    const value = JSON.parse(localStorage.getItem(KEY) || '{}');
    return value && typeof value === 'object' ? value : {};
  } catch { return {}; }
}

function store() {
  try {
    if (Object.keys(prefs).length) localStorage.setItem(KEY, JSON.stringify(prefs)); else localStorage.removeItem(KEY);
  } catch { /* storage blocked: the settings last until the page closes */ }
}

function notify() { listeners.forEach((fn) => { try { fn(); } catch (e) { console.error(e); } }); }

/** Another tab of this browser changed the settings. */
addEventListener('storage', (e) => { if (e.key === KEY) { prefs = load(); notify(); } });

export function onPrefsChange(fn) { listeners.add(fn); return () => listeners.delete(fn); }

/** Sets one setting; undefined goes back to the phone's value (or the default). */
export function setPref(key, value) {
  if (value === undefined || value === null) delete prefs[key]; else prefs[key] = value;
  store();
  notify();
}

export function resetPrefs() { prefs = {}; store(); notify(); }

/** The phone's theme with this browser's settings on top. */
export function effectiveTheme(phone) {
  const theme = { ...BROWSER_DEFAULTS, ...phone };
  for (const [k, v] of Object.entries(prefs)) if (v !== null && v !== undefined && (k in BROWSER_DEFAULTS || FROM_PHONE.includes(k))) theme[k] = v;
  return theme;
}

const clamp = (v, { min, max }) => Math.max(min, Math.min(max, v));

/** Ctrl+scroll and Ctrl+= / Ctrl+- in the editor; [delta] 0 goes back to the phone's size. */
export function bumpEditorFont(theme, delta) {
  if (!delta) { setPref('fontSize', undefined); return; }
  setPref('fontSize', clamp(Math.round((theme.fontSize || 13) + delta), EDITOR_FONT_SIZES));
}

/** Whether [name] is installed: text in it measures differently from the generic fonts. */
function installed(name) {
  const ctx = (installed.canvas ||= document.createElement('canvas')).getContext('2d');
  const sample = 'mmmmmmmmmmlli1O0{}[]=>';
  return ['monospace', 'serif', 'sans-serif'].some((base) => {
    ctx.font = `40px ${base}`;
    const plain = ctx.measureText(sample).width;
    ctx.font = `40px "${name}", ${base}`;
    return ctx.measureText(sample).width !== plain;
  });
}

/** The settings dialog; changes apply at once, everywhere in this browser. */
export async function openSettings(getPhoneTheme) {
  const body = h('div', { class: 'settings' });
  const fontsInstalled = new Map(FONTS.map((f) => [f, installed(f)]));
  let customFont = false;

  const render = () => {
    const phone = getPhoneTheme() || {};
    const t = effectiveTheme(phone);
    const focused = document.activeElement?.dataset?.focus;
    // A "back to the phone's value" button when this browser changed a setting.
    const reset = (key) => {
      if (!(key in prefs)) return null;
      const was = key in BROWSER_DEFAULTS ? 'the default' : `the phone's setting (${describe(key, phone[key])})`;
      return h('button', { class: 'reset', title: `Back to ${was}`, 'aria-label': `Back to ${was}`, dataset: { focus: `reset-${key}` }, onclick: () => setPref(key, undefined) }, icon('undo', 'i s'));
    };
    const row = (key, name, control, hint) => h('div', { class: 'row' },
      h('div', { class: 'name' }, h('div', null, name), hint ? h('div', { class: 'hint' }, hint) : null),
      reset(key), control);
    const stepper = (key, value, range, unit = '') => h('div', { class: 'stepper' },
      h('button', { title: 'Smaller', 'aria-label': 'Smaller', disabled: value <= range.min, dataset: { focus: `${key}-` }, onclick: () => setPref(key, clamp(value - 1, range)) }, icon('minus', 'i s')),
      h('span', { class: 'value' }, `${value}${unit}`),
      h('button', { title: 'Larger', 'aria-label': 'Larger', disabled: value >= range.max, dataset: { focus: `${key}+` }, onclick: () => setPref(key, clamp(value + 1, range)) }, icon('add', 'i s')));
    const toggle = (key, value) => h('label', { class: 'switch' },
      h('input', { type: 'checkbox', checked: !!value, dataset: { focus: key }, onchange: (e) => setPref(key, e.target.checked) }), h('span'));
    const select = (key, value, options, onPick) => h('select', { class: 'field', dataset: { focus: key }, onchange: (e) => onPick(e.target.value) },
      options.map(([v, label]) => h('option', { value: String(v), selected: String(v) === String(value) }, label)));
    const seg = (key, value, options, unit) => h('div', { class: 'seg' }, options.map((v) => h('button', {
      class: v === value ? 'on' : '', dataset: { focus: `${key}${v}` }, onclick: () => setPref(key, v),
    }, `${v}${unit}`)));

    const family = t.fontFamily || '';
    const knownFont = !family || FONTS.includes(family);
    const fontOptions = [
      ['', 'Default'],
      ...FONTS.map((f) => [f, fontsInstalled.get(f) ? f : `${f} (not installed)`]),
      ['\u0000custom', 'Other font installed on this computer…'],
    ];
    const showCustom = customFont || !knownFont;
    const custom = showCustom ? h('input', {
      class: 'field', value: knownFont ? '' : family, placeholder: 'Font name, e.g. Iosevka', spellcheck: false, dataset: { focus: 'font-custom' },
      onchange: (e) => { const v = e.target.value.replace(/["\\;{}]/g, '').trim(); customFont = !v; setPref('fontFamily', v || undefined); },
    }) : null;

    fill(body,
      h('p', { class: 'note' }, 'Kept in this browser only. They start from the phone\'s settings; the colors follow the theme chosen in the app.'),
      h('h3', null, 'Interface'),
      row('uiScale', 'Interface size', select('uiScale', t.uiScale, UI_SCALES.map((s) => [s, `${s}%`]), (v) => setPref('uiScale', Number(v) === 100 ? undefined : Number(v))),
        'Scales menus, panels and the editor together'),
      h('h3', null, 'Editor'),
      row('fontSize', 'Font size', stepper('fontSize', Math.round(t.fontSize || 13), EDITOR_FONT_SIZES, ' px'),
        `Also ${keyHint('Ctrl')}+scroll, ${keyHint('Ctrl+=')} and ${keyHint('Ctrl+-')} in the editor (${keyHint('Ctrl+0')} resets)`),
      row('fontFamily', 'Font', h('div', { class: 'font-pick' },
        select('fontFamily', showCustom ? '\u0000custom' : family, fontOptions, (v) => {
          if (v === '\u0000custom') { customFont = true; render(); body.querySelector('[data-focus="font-custom"]')?.focus(); return; }
          customFont = false;
          setPref('fontFamily', v || undefined);
        }), custom)),
      h('div', { class: 'preview', style: { fontFamily: 'var(--code)', fontSize: `${t.fontSize || 13}px`, lineHeight: String(t.lineHeight) } },
        h('div', null, '#include <stdio.h>  // 0O 1lI |'),
        h('div', null, 'int main(void) { return x != 0 && y >= 2 ? -1 : 0; }')),
      row('lineHeight', 'Line height', select('lineHeight', t.lineHeight, LINE_HEIGHTS.map((v) => [v, String(v)]), (v) => setPref('lineHeight', Number(v) === BROWSER_DEFAULTS.lineHeight ? undefined : Number(v)))),
      row('ligatures', 'Font ligatures', toggle('ligatures', t.ligatures), 'Joins symbols like != and => in fonts that have ligatures (Fira Code, JetBrains Mono)'),
      row('tabWidth', 'Tab width', seg('tabWidth', t.tabWidth || 4, TAB_WIDTHS, ' spaces')),
      row('wordWrap', 'Word wrap', toggle('wordWrap', t.wordWrap), 'Wrap long lines instead of scrolling sideways'),
      row('lineNumbers', 'Line numbers', toggle('lineNumbers', t.lineNumbers !== false)),
      row('highlightLine', 'Highlight current line', toggle('highlightLine', t.highlightLine !== false)),
      h('h3', null, 'Saving'),
      row('formatOnSave', 'Format on save', toggle('formatOnSave', t.formatOnSave), `C/C++ files go through clang-format when saved (${keyHint('Shift+Alt+F')} formats any time)`),
      row('autoSave', 'Auto save', toggle('autoSave', t.autoSave), 'Save a file a second after you stop typing'),
      h('h3', null, 'Terminal'),
      row('terminalFontSize', 'Font size', stepper('terminalFontSize', t.terminalFontSize || 12, TERMINAL_FONT_SIZES, ' px'), 'The terminal uses the editor font'));
    if (focused) body.querySelector(`[data-focus="${CSS.escape(focused)}"]`)?.focus();
  };

  const off = onPrefsChange(render);
  render();
  await dialog({
    title: 'Settings',
    body,
    actions: [
      { label: 'Reset to the phone\'s settings', onClick: () => { customFont = false; resetPrefs(); } },
      { label: 'Done', primary: true, value: true },
    ],
  });
  off();
}

function describe(key, value) {
  if (typeof value === 'boolean') return value ? 'on' : 'off';
  if (key === 'tabWidth') return `${value} spaces`;
  if (/FontSize|fontSize/.test(key)) return `${Math.round(value)} px`;
  return String(value);
}
