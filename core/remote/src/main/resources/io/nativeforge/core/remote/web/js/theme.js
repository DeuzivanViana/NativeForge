// Applies the app's theme: the palette as CSS variables, and the editor theme (a VS Code /
// TextMate theme, the same file the app's editor uses) as a CodeMirror theme.
import { EditorView } from '@codemirror/view';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';

/** The font stack of code (editor, terminal, build output) when no font is picked in Settings. */
export const MONO_FONTS = '"JetBrains Mono", "Cascadia Code", "Fira Code", Consolas, "Roboto Mono", "DejaVu Sans Mono", monospace';

/** [theme.fontFamily] first, then the default stack for characters it lacks. */
export function codeFont(theme) {
  const family = String(theme.fontFamily || '').replace(/["\\;{}]/g, '').trim();
  return family ? `"${family}", ${MONO_FONTS}` : MONO_FONTS;
}

export function applyPalette(theme) {
  const root = document.documentElement;
  for (const [name, value] of Object.entries(theme.colors || {})) root.style.setProperty(`--${name}`, value);
  root.style.colorScheme = theme.dark ? 'dark' : 'light';
  root.style.setProperty('--editor-font', `${theme.fontSize || 13}px`);
  root.style.setProperty('--code', codeFont(theme));
  root.style.setProperty('--editor-line-height', String(theme.lineHeight || 1.5));
  root.style.setProperty('--editor-ligatures', theme.ligatures === false ? 'none' : 'normal');
  // Interface size: CSS zoom scales the whole page, editor and terminal included.
  const zoom = Math.max(0.5, Math.min(3, (theme.uiScale || 100) / 100));
  const before = root.style.zoom || '1';
  root.style.zoom = zoom === 1 ? '' : String(zoom);
  root.style.setProperty('--zoom', String(zoom));
  if ((root.style.zoom || '1') !== before) dispatchEvent(new Event('resize'));
  let meta = document.querySelector('meta[name="theme-color"]');
  if (!meta) { meta = document.createElement('meta'); meta.name = 'theme-color'; document.head.append(meta); }
  meta.content = theme.colors?.panel || '#21252b';
}

/** Foreground and font style of the best TextMate rule for [scope], like VS Code picks it. */
function tokenStyle(rules, scope) {
  let best = null;
  let bestScore = -1;
  rules.forEach((rule, index) => {
    const selectors = Array.isArray(rule.scope) ? rule.scope : String(rule.scope ?? '').split(',');
    for (const raw of selectors) {
      const selector = raw.trim().split(/\s+/).pop(); // descendant selectors: judge the last part
      if (!selector) continue;
      if (scope === selector || scope.startsWith(`${selector}.`)) {
        const score = selector.split('.').length * 1000 + index; // more specific first, later rules win ties
        if (score > bestScore) { bestScore = score; best = rule.settings || {}; }
      }
    }
  });
  return best;
}

function style(rules, scopes, fallback) {
  for (const scope of scopes) {
    const s = tokenStyle(rules, scope);
    if (s && s.foreground) {
      const out = { color: s.foreground };
      const fs = s.fontStyle || '';
      if (fs.includes('italic')) out.fontStyle = 'italic';
      if (fs.includes('bold')) out.fontWeight = 'bold';
      if (fs.includes('underline')) out.textDecoration = 'underline';
      return out;
    }
  }
  return fallback ? { color: fallback } : {};
}

// Lezer tags of the C/C++ grammar, and the TextMate scopes the app's C/C++ grammar gives the same tokens.
const MAP = [
  [[t.lineComment, t.blockComment, t.comment], ['comment.line', 'comment.block', 'comment']],
  [[t.keyword, t.modifier, t.operatorKeyword, t.definitionKeyword], ['storage.modifier', 'keyword', 'storage']],
  [[t.controlKeyword], ['keyword.control', 'keyword']],
  [[t.processingInstruction, t.macroName], ['keyword.control.directive', 'meta.preprocessor', 'keyword.control', 'keyword']],
  [[t.string, t.character, t.special(t.string)], ['string.quoted', 'string']],
  [[t.escape], ['constant.character.escape', 'constant.character', 'string']],
  [[t.number, t.integer, t.float], ['constant.numeric', 'constant']],
  [[t.bool, t.null, t.atom], ['constant.language', 'constant']],
  [[t.standard(t.typeName)], ['storage.type.built-in', 'storage.type', 'support.type', 'entity.name.type']],
  [[t.typeName, t.className], ['entity.name.type', 'support.type', 'storage.type']],
  [[t.namespace], ['entity.name.namespace', 'entity.name.type', 'entity.name']],
  [[t.function(t.variableName), t.function(t.definition(t.variableName)), t.function(t.propertyName)], ['entity.name.function', 'support.function']],
  [[t.propertyName], ['variable.other.property', 'variable.other.member', 'variable.other', 'variable']],
  [[t.variableName, t.definition(t.variableName)], ['variable.other', 'variable']],
  [[t.labelName], ['entity.name.label', 'variable']],
  [[t.operator, t.derefOperator, t.arithmeticOperator, t.logicOperator, t.bitwiseOperator, t.compareOperator, t.updateOperator], ['keyword.operator']],
  [[t.punctuation, t.separator, t.bracket, t.paren, t.brace, t.squareBracket, t.angleBracket], ['punctuation']],
  [[t.invalid], ['invalid']],
  // Tags of the other project files (js/languages.js): CMake, Makefiles, YAML, XML, Markdown, diffs.
  [[t.special(t.variableName)], ['variable.parameter', 'variable.language', 'variable']],
  [[t.standard(t.variableName)], ['support.function', 'entity.name.function']],
  [[t.tagName], ['entity.name.tag']],
  [[t.attributeName], ['entity.other.attribute-name']],
  [[t.heading], ['markup.heading', 'entity.name.section']],
  [[t.strong], ['markup.bold']],
  [[t.emphasis], ['markup.italic']],
  [[t.link, t.url], ['markup.underline.link', 'string.other.link']],
  [[t.quote], ['markup.quote']],
  [[t.monospace], ['markup.inline.raw', 'markup.raw', 'string']],
  [[t.list], ['punctuation.definition.list', 'markup.list']],
  [[t.inserted], ['markup.inserted']],
  [[t.deleted], ['markup.deleted']],
  [[t.changed], ['markup.changed']],
  [[t.meta], ['meta.preprocessor', 'keyword.control.directive', 'comment']],
];

export function editorTheme(theme) {
  const vs = theme.editor || {};
  const c = vs.colors || {};
  const p = theme.colors || {};
  const rules = vs.tokenColors || [];
  const fg = c['editor.foreground'] || p.text;
  const bg = c['editor.background'] || p.bg;
  const dark = theme.dark !== false;

  const view = EditorView.theme({
    '&': { color: fg, backgroundColor: bg },
    '.cm-content': { caretColor: c['editorCursor.foreground'] || p.accent },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: c['editorCursor.foreground'] || p.accent, borderLeftWidth: '2px' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: c['editor.selectionBackground'] || 'rgba(97,175,239,.3)',
    },
    '.cm-activeLine': { backgroundColor: c['editor.lineHighlightBackground'] || 'rgba(255,255,255,.04)' },
    '.cm-gutters': { backgroundColor: c['editorGutter.background'] || bg, color: c['editorLineNumber.foreground'] || p.muted, border: 'none' },
    '.cm-activeLineGutter': { backgroundColor: 'transparent', color: c['editorLineNumber.activeForeground'] || fg },
    '.cm-selectionMatch': { backgroundColor: c['editor.selectionHighlightBackground'] || 'rgba(255,255,255,.08)' },
    '.cm-searchMatch': { backgroundColor: c['editor.findMatchHighlightBackground'] || 'rgba(229,192,123,.25)' },
    '.cm-searchMatch.cm-searchMatch-selected': { backgroundColor: c['editor.findMatchBackground'] || 'rgba(229,192,123,.45)' },
    '&.cm-focused .cm-matchingBracket': { backgroundColor: c['editorBracketMatch.background'] || 'rgba(255,255,255,.12)', outline: `1px solid ${c['editorBracketMatch.border'] || 'transparent'}` },
    '.cm-foldPlaceholder': { backgroundColor: p.raised, border: 'none', color: p.muted },
    '.cm-lintRange-error': { backgroundImage: 'none', textDecoration: `underline wavy ${c['editorError.foreground'] || p.error}`, textUnderlineOffset: '3px' },
    '.cm-lintRange-warning': { backgroundImage: 'none', textDecoration: `underline wavy ${c['editorWarning.foreground'] || p.warning}`, textUnderlineOffset: '3px' },
    '.cm-lintRange-info, .cm-lintRange-hint': { backgroundImage: 'none', textDecoration: `underline dotted ${p.accent}`, textUnderlineOffset: '3px' },
  }, { dark });

  const specs = MAP.map(([tags, scopes]) => ({ tag: tags, ...style(rules, scopes) })).filter((s) => Object.keys(s).length > 1);
  return [view, syntaxHighlighting(HighlightStyle.define(specs, { themeType: dark ? 'dark' : 'light' }))];
}

/** xterm.js colors from the app palette (and the editor theme's terminal colors when it has them). */
export function terminalTheme(theme) {
  const p = theme.colors || {};
  const c = theme.editor?.colors || {};
  const pick = (k, d) => c[`terminal.${k}`] || d;
  return {
    background: p.bg, foreground: p.text, cursor: p.accent, cursorAccent: p.bg,
    selectionBackground: 'rgba(128,128,128,.35)',
    black: pick('ansiBlack', '#3f4451'), red: pick('ansiRed', p.error), green: pick('ansiGreen', p.success), yellow: pick('ansiYellow', p.warning),
    blue: pick('ansiBlue', p.accent), magenta: pick('ansiMagenta', p.secondary), cyan: pick('ansiCyan', '#56b6c2'), white: pick('ansiWhite', '#d7dae0'),
    brightBlack: pick('ansiBrightBlack', '#5c6370'), brightRed: pick('ansiBrightRed', p.error), brightGreen: pick('ansiBrightGreen', p.success),
    brightYellow: pick('ansiBrightYellow', p.warning), brightBlue: pick('ansiBrightBlue', p.accent), brightMagenta: pick('ansiBrightMagenta', p.secondary),
    brightCyan: pick('ansiBrightCyan', '#56b6c2'), brightWhite: pick('ansiBrightWhite', '#ffffff'),
  };
}
