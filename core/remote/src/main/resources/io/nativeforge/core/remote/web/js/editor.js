// CodeMirror 6 editor with clangd features through the server (completion, live
// diagnostics, hover, go to definition, formatting).
import { EditorState, Compartment, EditorSelection } from '@codemirror/state';
import {
  EditorView, keymap, lineNumbers, highlightActiveLineGutter, highlightSpecialChars, drawSelection,
  dropCursor, rectangularSelection, crosshairCursor, highlightActiveLine, hoverTooltip,
} from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import {
  indentOnInput, bracketMatching, foldGutter, foldKeymap, indentUnit, syntaxHighlighting, defaultHighlightStyle,
} from '@codemirror/language';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, startCompletion } from '@codemirror/autocomplete';
import { lintGutter, setDiagnostics, lintKeymap } from '@codemirror/lint';
import { cpp } from '@codemirror/lang-cpp';
import { editorTheme } from './theme.js';
import { debounce } from './util.js';

const CLANGD_EXT = new Set(['c', 'h', 'cpp', 'cc', 'cxx', 'c++', 'hpp', 'hh', 'hxx', 'inl', 'ipp', 'm', 'mm']);
export const isClangdFile = (path) => CLANGD_EXT.has(path.split('.').pop().toLowerCase());

export function lspPosition(doc, offset) {
  const line = doc.lineAt(offset);
  return { line: line.number - 1, ch: offset - line.from };
}

export function offsetOf(doc, pos) {
  const lineNo = Math.min(Math.max(pos.line + 1, 1), doc.lines);
  const line = doc.line(lineNo);
  return Math.min(line.from + Math.max(pos.ch, 0), line.to);
}

const KIND = {
  function: 'function', method: 'method', constructor: 'function', field: 'property', variable: 'variable',
  class: 'class', struct: 'class', interface: 'interface', module: 'namespace', property: 'property',
  enum: 'enum', enum_member: 'constant', keyword: 'keyword', snippet: 'text', constant: 'constant',
  type_parameter: 'type', text: 'text', file: 'text', folder: 'text', reference: 'variable',
};

/** Plain text of a clangd snippet: "${1:x}" becomes "x", "$0" disappears. */
const plain = (s) => s.replace(/\$\{\d+:([^}]*)\}/g, '$1').replace(/\$\{\d+\}|\$\d+/g, '');

function applyEdits(view, edits) {
  const doc = view.state.doc;
  const changes = edits.map((e) => ({ from: offsetOf(doc, e.range.start), to: offsetOf(doc, e.range.end), insert: e.text }));
  if (changes.length) view.dispatch({ changes: view.state.changes(changes.sort((a, b) => a.from - b.from)) });
}

/**
 * One editor per open file. ctx: { project, path, channel, theme, onChange(dirty), onSave(),
 * onDefinition(location), commands }.
 */
export class FileEditor {
  constructor(parent, text, ctx) {
    this.ctx = ctx;
    this.savedText = text;
    this.clangd = isClangdFile(ctx.path);
    this.themeConf = new Compartment();
    this.prefsConf = new Compartment();
    const syncLater = debounce(() => this.sync(), 350);

    const extensions = [
      highlightSpecialChars(), history(), drawSelection(), dropCursor(),
      EditorState.allowMultipleSelections.of(true), indentOnInput(), bracketMatching(), closeBrackets(),
      rectangularSelection(), crosshairCursor(), highlightSelectionMatches(),
      foldGutter({ markerDOM: (open) => { const s = document.createElement('span'); s.textContent = open ? '⌄' : '›'; s.style.color = 'var(--muted)'; return s; } }),
      lintGutter(),
      this.prefsConf.of(this.prefs(ctx.theme)),
      this.themeConf.of(editorTheme(ctx.theme)),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      keymap.of([
        { key: 'F12', run: () => { this.definition(); return true; } },
        { key: 'Shift-Alt-f', run: () => { this.format(); return true; } },
        { key: 'Mod-Space', run: startCompletion },
        ...(ctx.commands || []),
        ...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, ...foldKeymap,
        ...completionKeymap, ...lintKeymap, indentWithTab,
      ]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) {
          ctx.onChange(this.dirty);
          if (this.clangd) syncLater();
        }
        if (u.selectionSet || u.docChanged) ctx.onCursor?.(this.cursor());
      }),
      EditorView.domEventHandlers({
        mousedown: (e, view) => {
          if (!(e.ctrlKey || e.metaKey) || e.button !== 0) return false;
          const pos = view.posAtCoords({ x: e.clientX, y: e.clientY });
          if (pos == null) return false;
          e.preventDefault();
          view.dispatch({ selection: { anchor: pos } });
          this.definition();
          return true;
        },
      }),
    ];
    if (/\.(c|h|cpp|cc|cxx|c\+\+|hpp|hh|hxx|inl|ipp|m|mm|glsl|vert|frag|comp)$/i.test(ctx.path)) extensions.push(cpp());
    if (this.clangd) {
      extensions.push(
        autocompletion({ override: [(c) => this.complete(c)], activateOnTypingDelay: 120, maxRenderedOptions: 100, icons: true }),
        hoverTooltip((view, pos) => this.hover(view, pos), { hoverTime: 450 }),
      );
    } else {
      extensions.push(autocompletion());
    }

    this.view = new EditorView({ state: EditorState.create({ doc: text, extensions }), parent });
    if (this.clangd) this.sync();
  }

  prefs(theme) {
    const tab = theme.tabWidth || 4;
    return [
      EditorState.tabSize.of(tab),
      indentUnit.of(' '.repeat(tab)),
      theme.lineNumbers !== false ? [lineNumbers(), highlightActiveLineGutter()] : [],
      theme.highlightLine !== false ? highlightActiveLine() : [],
      theme.wordWrap ? EditorView.lineWrapping : [],
    ];
  }

  setTheme(theme) {
    this.ctx.theme = theme;
    this.view.dispatch({ effects: [this.themeConf.reconfigure(editorTheme(theme)), this.prefsConf.reconfigure(this.prefs(theme))] });
  }

  get text() { return this.view.state.doc.toString(); }
  get dirty() { return this.text !== this.savedText; }
  markSaved(text) { this.savedText = text; this.ctx.onChange(this.dirty); }

  cursor() {
    const head = this.view.state.selection.main.head;
    const line = this.view.state.doc.lineAt(head);
    return { line: line.number, col: head - line.from + 1 };
  }

  focus() { this.view.focus(); }
  destroy() {
    if (this.clangd) this.ctx.channel.send({ t: 'lsp.close', project: this.ctx.project, path: this.ctx.path });
    this.view.destroy();
  }

  /** Replaces the whole text (file changed on the phone) keeping the cursor where possible. */
  replaceText(text) {
    const head = Math.min(this.view.state.selection.main.head, text.length);
    this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: text }, selection: { anchor: head } });
    this.markSaved(text);
  }

  goTo(line, col = 1) {
    const doc = this.view.state.doc;
    const l = doc.line(Math.min(Math.max(line, 1), doc.lines));
    const pos = Math.min(l.from + Math.max(col - 1, 0), l.to);
    this.view.dispatch({ selection: EditorSelection.cursor(pos), effects: EditorView.scrollIntoView(pos, { y: 'center' }) });
    this.view.focus();
  }

  goToRange(range) {
    const doc = this.view.state.doc;
    const from = offsetOf(doc, range.start);
    const to = offsetOf(doc, range.end);
    this.view.dispatch({ selection: EditorSelection.range(from, to), effects: EditorView.scrollIntoView(from, { y: 'center' }) });
    this.view.focus();
  }

  setDiagnostics(items) {
    const doc = this.view.state.doc;
    const list = (items || []).map((d) => {
      const from = offsetOf(doc, d.range.start);
      let to = offsetOf(doc, d.range.end);
      if (to <= from) to = Math.min(from + 1, doc.length);
      return { from, to, severity: d.severity === 'hint' ? 'hint' : d.severity, message: d.message, source: d.source || 'clangd' };
    });
    this.view.dispatch(setDiagnostics(this.view.state, list));
  }

  request(type, extra = {}) {
    const state = this.view.state;
    const pos = lspPosition(state.doc, extra.pos ?? state.selection.main.head);
    return this.ctx.channel.request(type, { project: this.ctx.project, path: this.ctx.path, text: state.doc.toString(), line: pos.line, ch: pos.ch, ...extra.payload });
  }

  sync() {
    this.ctx.channel.send({ t: 'lsp.sync', project: this.ctx.project, path: this.ctx.path, text: this.text });
  }

  async complete(context) {
    const word = context.matchBefore(/[A-Za-z_$][\w$]*/);
    const before = context.state.sliceDoc(Math.max(0, context.pos - 2), context.pos);
    const member = /(\.|->|::)$/.test(before);
    if (!context.explicit && !member && (!word || word.from === word.to)) return null;
    let result;
    try { result = await this.request('lsp.complete', { pos: context.pos }); } catch { return null; }
    if (!result || context.aborted) return null;
    const from = word ? word.from : context.pos;
    const options = result.items.map((item, i) => ({
      label: item.filter || item.label,
      displayLabel: item.label,
      detail: item.detail || undefined,
      info: item.doc || undefined,
      type: KIND[item.kind] || 'text',
      boost: -i / 1000,
      apply: (view, _completion, f, to) => {
        const doc = view.state.doc;
        const start = item.range ? Math.min(offsetOf(doc, item.range.start), f) : f;
        const insert = plain(item.insert);
        const changes = [{ from: start, to, insert }];
        for (const e of item.extra || []) changes.push({ from: offsetOf(doc, e.range.start), to: offsetOf(doc, e.range.end), insert: e.text });
        const set = view.state.changes(changes.sort((a, b) => a.from - b.from));
        view.dispatch({ changes: set, selection: { anchor: set.mapPos(to, 1) }, userEvent: 'input.complete' });
      },
    }));
    return { from, options, validFor: result.incomplete ? undefined : /^[\w$]*$/, filter: true };
  }

  async hover(view, pos) {
    let text;
    try { text = await this.request('lsp.hover', { pos }); } catch { return null; }
    if (!text) return null;
    return {
      pos,
      create() {
        const dom = document.createElement('div');
        dom.className = 'cm-hover';
        dom.textContent = text.replace(/```\w*\n?/g, '').replace(/\n{3,}/g, '\n\n').trim();
        return { dom };
      },
    };
  }

  async definition() {
    if (!this.clangd) return;
    try {
      const found = await this.request('lsp.definition');
      if (found?.length) this.ctx.onDefinition(found[0]);
      else this.ctx.onDefinition(null);
    } catch { /* ignore */ }
  }

  async format() {
    if (!this.clangd) return;
    try {
      const edits = await this.request('lsp.format', { payload: { tabSize: this.ctx.theme.tabWidth || 4 } });
      if (edits) applyEdits(this.view, edits);
    } catch { /* ignore */ }
  }
}
