// Syntax highlighting by file name, like the app's editor (feature/editor EditorLanguages.kt):
// C/C++ and GLSL with the Lezer C++ grammar, the other files of a C/C++ project (CMake,
// Makefiles, JSON, YAML, TOML, INI, shell, Python, XML, Markdown, diffs) with stream modes.
import { StreamLanguage } from '@codemirror/language';
import { cpp } from '@codemirror/lang-cpp';
import { cmake } from '@codemirror/legacy-modes/mode/cmake';
import { shell } from '@codemirror/legacy-modes/mode/shell';
import { yaml } from '@codemirror/legacy-modes/mode/yaml';
import { toml } from '@codemirror/legacy-modes/mode/toml';
import { properties } from '@codemirror/legacy-modes/mode/properties';
import { xml } from '@codemirror/legacy-modes/mode/xml';
import { json } from '@codemirror/legacy-modes/mode/javascript';
import { diff } from '@codemirror/legacy-modes/mode/diff';
import { python } from '@codemirror/legacy-modes/mode/python';

const hash = { commentTokens: { line: '#' } };

/** A stream mode with the comment tokens Ctrl+/ uses, and some token names changed. */
function mode(base, languageData, rename = {}) {
  return {
    ...base,
    token: (stream, state) => { const t = base.token(stream, state); return (t && rename[t]) || t; },
    languageData: { ...(base.languageData || {}), ...languageData },
  };
}

// CMake commands are "def" tokens there; colour them as function calls.
const cmakeMode = mode(cmake, hash, { def: 'variableName.function' });
const shellMode = mode(shell, hash);
const pythonMode = mode(python, hash);
const iniMode = mode(properties, hash);
// Object keys are "string property"; "property" alone is not a highlighting tag.
const jsonMode = mode(json, {}, { 'string property': 'propertyName' });

const MAKE_DIRECTIVE = /^(?:-?include|sinclude|ifn?eq|ifn?def|else|endif|define|endef|(?:un)?export|override|vpath|private)\b/;

/** GNU make: comments, directives, targets, variable definitions and $(references). */
export const makefile = {
  name: 'makefile',
  startState: () => ({ recipe: false }),
  token(stream, state) {
    if (stream.sol()) state.recipe = stream.peek() === '\t';
    if (stream.eatSpace()) return null;
    if (stream.match(/^\$(?:\([^)]*\)|\{[^}]*\}|.)/)) return 'variableName.special';
    if (stream.peek() === '#') { stream.skipToEnd(); return 'comment'; }
    if (state.recipe) { stream.eatWhile(/[^$#]/); return null; }
    const lineStart = stream.string.slice(0, stream.pos).trim() === '';
    if (lineStart && stream.match(MAKE_DIRECTIVE)) return 'keyword';
    if (lineStart && stream.match(/^[\w.\-/%]+(?=\s*(?:[:+?!]|::)?=)/)) return 'variableName.definition';
    if (lineStart && stream.match(/^[^:#=$\s][^:#=$]*?(?=\s*::?(?!=))/)) return 'variableName.function';
    if (stream.match(/^(?:::?=|[+?!]?=|::?|\|)/)) return 'operator';
    stream.next();
    stream.eatWhile(/[^$#:=\s]/);
    return null;
  },
  languageData: hash,
};

/** Markdown: headings, lists, quotes, code (fenced and inline), emphasis and links. */
export const markdown = {
  name: 'markdown',
  startState: () => ({ fence: null }),
  token(stream, state) {
    if (state.fence) {
      if (stream.sol() && stream.match(state.fence)) { stream.skipToEnd(); state.fence = null; return 'meta'; }
      stream.skipToEnd();
      return 'monospace';
    }
    if (stream.sol()) {
      const fence = stream.match(/^\s{0,3}(`{3,}|~{3,})/);
      if (fence) { state.fence = new RegExp(`^\\s{0,3}${fence[1][0]}{${fence[1].length},}\\s*$`); stream.skipToEnd(); return 'meta'; }
      if (stream.match(/^\s{0,3}#{1,6}(?:\s|$)/)) { stream.skipToEnd(); return 'heading'; }
      if (stream.match(/^\s{0,3}(?:[-*_]\s*){3,}$/)) return 'contentSeparator';
      if (stream.match(/^\s{0,3}>/)) return 'quote';
      if (stream.match(/^\s*(?:[-*+]|\d+[.)])(?=\s)/)) return 'list';
    }
    if (stream.match(/^`+[^`]*`+/)) return 'monospace';
    if (stream.match(/^(?:\*\*|__)(?=\S)[^]*?\S(?:\*\*|__)/)) return 'strong';
    if (stream.match(/^[*_](?=[^\s*_])[^*_]*[^\s*_][*_]/) || stream.match(/^[*_][^\s*_][*_]/)) return 'emphasis';
    if (stream.match(/^!?\[[^\]]*\](?:\([^)]*\)|\[[^\]]*\])/)) return 'link';
    if (stream.match(/^<(?:https?:\/\/|mailto:)[^>]+>/) || stream.match(/^https?:\/\/[^\s)>]+/)) return 'url';
    if (stream.match(/^<!--.*?-->/)) return 'comment';
    stream.next();
    stream.eatWhile(/[^`*_![<h\\]/);
    return null;
  },
  languageData: { commentTokens: { block: { open: '<!--', close: '-->' } } },
};

const C_FAMILY = /\.(?:c|h|cpp|cc|cxx|c\+\+|hpp|hh|hxx|inl|ipp|tpp|m|mm|glsl|vert|frag|geom|comp|tesc|tese|vsh|fsh)$/i;

const BY_NAME = {
  'cmakelists.txt': cmakeMode,
  makefile, gnumakefile: makefile,
  '.clang-format': yaml, '_clang-format': yaml, '.clangd': yaml, '.clang-tidy': yaml,
  '.editorconfig': iniMode, '.gitconfig': iniMode, '.gitmodules': iniMode,
  '.bashrc': shellMode, '.profile': shellMode, '.bash_profile': shellMode, '.zshrc': shellMode, configure: shellMode, gradlew: shellMode,
};

const BY_EXTENSION = {
  cmake: cmakeMode,
  json: jsonMode, jsonc: jsonMode, json5: jsonMode,
  yml: yaml, yaml,
  toml,
  ini: iniMode, cfg: iniMode, conf: iniMode, properties: iniMode,
  mk: makefile, mak: makefile, make: makefile,
  sh: shellMode, bash: shellMode, zsh: shellMode, ksh: shellMode,
  py: pythonMode, pyw: pythonMode,
  md: markdown, markdown,
  xml, svg: xml, plist: xml, xsd: xml, tmx: xml,
  diff, patch: diff,
};

const languages = new Map();

/** The highlighting extension for a file, or null for plain text. */
export function languageFor(path) {
  if (C_FAMILY.test(path)) return cpp();
  const name = path.split('/').pop().toLowerCase();
  const spec = BY_NAME[name] || (name.endsWith('.cmake.in') ? cmakeMode : BY_EXTENSION[name.includes('.') ? name.split('.').pop() : '']);
  if (!spec) return null;
  if (!languages.has(spec)) languages.set(spec, StreamLanguage.define(spec));
  return languages.get(spec);
}
