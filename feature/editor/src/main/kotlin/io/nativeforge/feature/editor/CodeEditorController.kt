package io.nativeforge.feature.editor

import android.graphics.Typeface
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.nativeforge.core.lsp.DiagnosticSeverity
import io.nativeforge.core.lsp.LspDiagnostic
import io.nativeforge.core.lsp.Position
import io.nativeforge.core.lsp.TextEdit
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SelectionMovement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A file opened in the editor. Keeping its [Content] preserves undo history across tab switches. */
class EditorDocument(val path: String, initialText: String) {
    internal val content: Content = Content(initialText)
    val fileName: String get() = path.substringAfterLast('/')
    fun text(): String = content.toString()

    /** The same text under another path (after a rename). */
    fun copyTo(newPath: String): EditorDocument = EditorDocument(newPath, text())
}

/** User-adjustable editor options (Settings screen). */
data class EditorPreferences(
    val theme: EditorTheme = EditorTheme.DEFAULT,
    val fontSizeSp: Float = 13f,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val tabWidth: Int = 4,
    val highlightCurrentLine: Boolean = true,
)

/** Observable editor state for toolbars and the status bar. */
data class EditorUiState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** 1-based caret position. */
    val line: Int = 1,
    val column: Int = 1,
    val hasSelection: Boolean = false,
    val searchMatches: Int = 0,
    val searchIndex: Int = -1,
)

enum class CaretMove { LEFT, RIGHT, UP, DOWN, LINE_START, LINE_END, WORD_LEFT, WORD_RIGHT }

/**
 * Imperative bridge between Compose state and Sora's [CodeEditor] view.
 * Screens talk to this controller; only this class touches the view.
 */
class CodeEditorController {
    private var editor: CodeEditor? = null
    private var pendingDocument: EditorDocument? = null
    private var preferences = EditorPreferences()
    private var appliedTheme: EditorTheme? = null
    var onContentChanged: ((EditorDocument) -> Unit)? = null

    /** Semantic completion for the open file (clangd), or null for word completion only. */
    @Volatile var intelligence: EditorIntelligence? = null

    /** Called with the caret's file position when the editor gains focus (split editor). */
    var onFocused: (() -> Unit)? = null
    private var diagnostics: Pair<String, List<LspDiagnostic>>? = null

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    var document: EditorDocument? = null
        private set

    internal fun attach(view: CodeEditor, preferences: EditorPreferences) {
        editor = view
        appliedTheme = null
        view.typefaceText = Typeface.MONOSPACE
        view.typefaceLineNumber = Typeface.MONOSPACE
        view.setPinLineNumber(true)
        applyPreferences(preferences)
        view.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            // Loading a file replaces the text too; only user edits make a document dirty.
            if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                document?.let { onContentChanged?.invoke(it) }
            }
            if (event.action == ContentChangeEvent.ACTION_INSERT && isMemberAccessTrigger(event)) {
                view.post { requestCompletion() }
            }
            refreshUndoState()
        }
        view.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            _state.update {
                it.copy(line = event.left.line + 1, column = event.left.column + 1, hasSelection = event.isSelected)
            }
            refreshSearchState()
        }
        view.setOnFocusChangeListener { _, focused -> if (focused) onFocused?.invoke() }
        (pendingDocument ?: document)?.let(::open)
        pendingDocument = null
    }

    /** `.`, `->` and `::` open the completion list, as in desktop IDEs. */
    private fun isMemberAccessTrigger(event: ContentChangeEvent): Boolean {
        val inserted = event.changedText
        if (inserted.length != 1 || intelligence == null) return false
        val text = event.editor.text
        val line = event.changeStart.line
        val column = event.changeStart.column
        val previous = if (column > 0) text.charAt(line, column - 1) else ' '
        return when (inserted[0]) {
            '.' -> !previous.isDigit()
            '>' -> previous == '-'
            ':' -> previous == ':'
            else -> false
        }
    }

    internal fun detach() {
        editor = null
    }

    /** Applies theme, font and layout options; cheap when nothing changed. */
    fun applyPreferences(preferences: EditorPreferences) {
        this.preferences = preferences
        val view = editor ?: return
        if (appliedTheme != preferences.theme) {
            appliedTheme = preferences.theme
            TextMateBootstrap.applyTheme(preferences.theme)
            view.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        }
        view.setTextSize(preferences.fontSizeSp)
        if (view.isWordwrap != preferences.wordWrap) view.isWordwrap = preferences.wordWrap
        view.isLineNumberEnabled = preferences.lineNumbers
        view.tabWidth = preferences.tabWidth
        view.isHighlightCurrentLine = preferences.highlightCurrentLine
    }

    fun open(document: EditorDocument) {
        this.document = document
        val view = editor ?: run { pendingDocument = document; return }
        view.setText(document.content)
        val scope = EditorLanguages.scopeFor(document.fileName)
        view.setEditorLanguage(
            if (scope != null) IntelligentLanguage(TextMateLanguage.create(scope, true), document.path) { intelligence } else EmptyLanguage(),
        )
        applyDiagnostics()
        refreshUndoState()
    }

    /** Shows an empty editor (the last open file was closed or deleted). */
    fun close() {
        document = null
        pendingDocument = null
        editor?.let { view ->
            view.setText("")
            view.setEditorLanguage(EmptyLanguage())
        }
        refreshUndoState()
    }

    /** Live diagnostics (squiggles and tooltips) for [path]; shown when that file is open. */
    fun setDiagnostics(path: String, list: List<LspDiagnostic>) {
        diagnostics = path to list
        applyDiagnostics()
    }

    private fun applyDiagnostics() {
        val view = editor ?: return
        val (path, list) = diagnostics ?: return
        if (path != document?.path) {
            view.diagnostics = null
            return
        }
        val text = view.text
        val container = DiagnosticsContainer()
        list.forEachIndexed { index, d ->
            val start = charIndex(text, d.range.start)
            var end = charIndex(text, d.range.end)
            if (end <= start) end = (start + 1).coerceAtMost(text.length)
            val severity = when (d.severity) {
                DiagnosticSeverity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
                DiagnosticSeverity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
                else -> DiagnosticRegion.SEVERITY_TYPO
            }
            val source = listOfNotNull(d.source, d.code).joinToString(" ")
            container.addDiagnostic(DiagnosticRegion(start, end, severity, index.toLong(), DiagnosticDetail(d.message, source.ifBlank { null }, null, null)))
        }
        view.diagnostics = container
    }

    private fun charIndex(text: Content, position: Position): Int {
        if (text.lineCount == 0) return 0
        val line = position.line.coerceIn(0, text.lineCount - 1)
        return text.getCharIndex(line, position.character.coerceIn(0, text.getColumnCount(line)))
    }

    /** The caret as an LSP position (0-based line and UTF-16 column). */
    fun caretPosition(): Position? {
        val cursor = editor?.cursor ?: return null
        return Position(cursor.leftLine, cursor.leftColumn)
    }

    /** The identifier under the caret, for rename dialogs. */
    fun wordAtCaret(): String {
        val view = editor ?: return ""
        val cursor = view.cursor
        if (cursor.isSelected) return view.text.subSequence(cursor.left, cursor.right).toString()
        val line = view.text.getLineString(cursor.leftLine)
        var start = cursor.leftColumn
        var end = cursor.leftColumn
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) end++
        return line.substring(start, end)
    }

    /** Opens the completion list now (Ctrl+Space). */
    fun requestCompletion() {
        runCatching { editor?.getComponent(EditorAutoCompletion::class.java)?.requireCompletion() }
    }

    /** Applies LSP text edits to the open document as one undo step. */
    fun applyEdits(edits: List<TextEdit>) {
        val view = editor ?: return
        val text = view.text
        text.beginBatchEdit()
        try {
            edits.sortedWith(compareByDescending<TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character })
                .forEach { edit ->
                    val start = charIndex(text, edit.range.start)
                    val end = charIndex(text, edit.range.end)
                    val s = text.indexer.getCharPosition(start)
                    val e = text.indexer.getCharPosition(end)
                    text.replace(s.line, s.column, e.line, e.column, edit.newText)
                }
        } finally {
            text.endBatchEdit()
        }
    }

    /** Applies replacements given as character offsets (clang-format) as one undo step. */
    fun applyReplacements(replacements: List<Triple<Int, Int, String>>) {
        val view = editor ?: return
        val text = view.text
        text.beginBatchEdit()
        try {
            replacements.sortedByDescending { it.first }.forEach { (offset, length, value) ->
                val s = text.indexer.getCharPosition(offset.coerceIn(0, text.length))
                val e = text.indexer.getCharPosition((offset + length).coerceIn(0, text.length))
                text.replace(s.line, s.column, e.line, e.column, value)
            }
        } finally {
            text.endBatchEdit()
        }
    }

    /** Moves the caret's line (or the selected lines) up or down by one. */
    fun moveLines(down: Boolean) {
        val view = editor ?: return
        val text = view.text
        val cursor = view.cursor
        val first = cursor.leftLine
        val last = if (cursor.isSelected && cursor.rightColumn == 0 && cursor.rightLine > first) cursor.rightLine - 1 else cursor.rightLine
        if (down && last >= text.lineCount - 1 || !down && first == 0) return
        val block = (first..last).joinToString("\n") { text.getLineString(it) }
        val column = cursor.leftColumn
        text.beginBatchEdit()
        try {
            if (down) {
                val below = text.getLineString(last + 1)
                text.replace(first, 0, last + 1, text.getColumnCount(last + 1), below + "\n" + block)
            } else {
                val above = text.getLineString(first - 1)
                text.replace(first - 1, 0, last, text.getColumnCount(last), block + "\n" + above)
            }
        } finally {
            text.endBatchEdit()
        }
        val target = if (down) first + 1 else first - 1
        view.setSelection(target, column.coerceAtMost(text.getColumnCount(target)))
    }

    /** Deletes the caret's line (Ctrl+Shift+K). */
    fun deleteLine() {
        val view = editor ?: return
        val text = view.text
        val line = view.cursor.leftLine
        if (text.lineCount <= 1) {
            text.delete(0, 0, 0, text.getColumnCount(0))
        } else if (line < text.lineCount - 1) {
            text.delete(line, 0, line + 1, 0)
        } else {
            text.delete(line - 1, text.getColumnCount(line - 1), line, text.getColumnCount(line))
        }
    }

    /** Gives the editor keyboard focus. */
    fun focus() { editor?.requestFocus() }

    /** Moves the caret to a 1-based [line]/[column], e.g. when a diagnostic is tapped. */
    fun goTo(line: Int, column: Int = 1) {
        val view = editor ?: return
        val targetLine = (line - 1).coerceIn(0, (view.text.lineCount - 1).coerceAtLeast(0))
        val targetColumn = (column - 1).coerceIn(0, view.text.getColumnCount(targetLine))
        view.setSelection(targetLine, targetColumn)
        view.ensureSelectionVisible()
    }

    /** Inserts [text] at the caret and moves the caret by [caretOffset] characters inside it. */
    fun insert(text: String, caretOffset: Int = text.length) {
        editor?.insertText(text, caretOffset)
    }

    /** Inserts one indentation step (spaces, [EditorPreferences.tabWidth] wide). */
    fun insertTab() = insert(" ".repeat(preferences.tabWidth))

    fun undo() { editor?.undo(); refreshUndoState() }
    fun redo() { editor?.redo(); refreshUndoState() }

    fun move(direction: CaretMove) {
        editor?.moveSelection(
            when (direction) {
                CaretMove.LEFT -> SelectionMovement.LEFT
                CaretMove.RIGHT -> SelectionMovement.RIGHT
                CaretMove.UP -> SelectionMovement.UP
                CaretMove.DOWN -> SelectionMovement.DOWN
                CaretMove.LINE_START -> SelectionMovement.LINE_START
                CaretMove.LINE_END -> SelectionMovement.LINE_END
                CaretMove.WORD_LEFT -> SelectionMovement.PREVIOUS_WORD_BOUNDARY
                CaretMove.WORD_RIGHT -> SelectionMovement.NEXT_WORD_BOUNDARY
            },
        )
    }

    fun selectAll() { editor?.selectAll() }
    fun copy() { editor?.copyText() }
    fun cut() { editor?.cutText() }
    fun paste() { editor?.pasteText() }
    fun duplicateLine() { editor?.duplicateLine() }
    fun showKeyboard() { editor?.showSoftInput() }
    fun hideKeyboard() { editor?.hideSoftInput() }

    /** Toggles a line comment (`//`, or `#` in CMake files) on every selected line, as one undo step. */
    fun toggleComment() {
        val view = editor ?: return
        val prefix = if (EditorLanguages.scopeFor(document?.fileName.orEmpty()) == "source.cmake") "#" else "//"
        val text = view.text
        val cursor = view.cursor
        val first = cursor.leftLine
        val last = if (cursor.isSelected && cursor.rightColumn == 0 && cursor.rightLine > first) cursor.rightLine - 1 else cursor.rightLine
        val lines = (first..last).map { text.getLineString(it) }
        val commented = lines.filter { it.isNotBlank() }.let { nonBlank ->
            nonBlank.isNotEmpty() && nonBlank.all { it.trimStart().startsWith(prefix) }
        }
        text.beginBatchEdit()
        try {
            lines.forEachIndexed { index, line ->
                val lineNumber = first + index
                val indent = line.length - line.trimStart().length
                if (commented) {
                    if (line.trimStart().startsWith(prefix)) {
                        val removeSpace = line.startsWith("$prefix ", indent)
                        text.delete(lineNumber, indent, lineNumber, indent + prefix.length + if (removeSpace) 1 else 0)
                    }
                } else if (line.isNotBlank()) {
                    text.insert(lineNumber, indent, "$prefix ")
                }
            }
        } finally {
            text.endBatchEdit()
        }
    }

    /** Highlights every match of [query]; an empty query clears the search. */
    fun search(query: String, ignoreCase: Boolean = true) {
        val view = editor ?: return
        if (query.isEmpty()) {
            view.searcher.stopSearch()
        } else {
            view.searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, ignoreCase))
        }
        refreshSearchState()
    }

    fun findNext() { runCatching { editor?.searcher?.gotoNext() }; refreshSearchState() }
    fun findPrevious() { runCatching { editor?.searcher?.gotoPrevious() }; refreshSearchState() }

    fun replaceCurrent(replacement: String) {
        runCatching { editor?.searcher?.replaceCurrentMatch(replacement) }
        refreshSearchState()
    }

    fun replaceAll(replacement: String) {
        runCatching { editor?.searcher?.replaceAll(replacement) }
        refreshSearchState()
    }

    fun stopSearch() {
        editor?.searcher?.stopSearch()
        _state.update { it.copy(searchMatches = 0, searchIndex = -1) }
    }

    private fun refreshUndoState() {
        val view = editor ?: return
        _state.update { it.copy(canUndo = view.canUndo(), canRedo = view.canRedo()) }
    }

    private fun refreshSearchState() {
        val searcher = editor?.searcher ?: return
        val (count, index) = runCatching {
            if (searcher.hasQuery()) searcher.matchedPositionCount to searcher.currentMatchedPositionIndex else 0 to -1
        }.getOrDefault(0 to -1)
        _state.update { it.copy(searchMatches = count, searchIndex = index) }
    }
}
