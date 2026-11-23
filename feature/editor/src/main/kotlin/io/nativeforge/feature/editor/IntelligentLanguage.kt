package io.nativeforge.feature.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.QuickQuoteHandler
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionIconDrawer
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.nativeforge.core.lsp.CompletionKind
import io.nativeforge.core.lsp.CompletionResult
import io.nativeforge.core.lsp.Position
import io.nativeforge.core.lsp.TextEdit
import io.nativeforge.core.lsp.CompletionItem as LspCompletionItem

/**
 * Semantic services the editor can use for the open file. Implemented by the app
 * (backed by clangd); the editor module only depends on the LSP data types.
 */
interface EditorIntelligence {
    /**
     * Completion at [position] in [text] (the current contents of [path]). Called on the
     * editor's completion thread, so it may block; null falls back to word completion.
     */
    fun completion(path: String, text: String, position: Position): CompletionResult?
}

/**
 * Wraps the TextMate language (highlighting, brackets, indentation) and replaces its
 * word-based completion with [intelligence] when it is available for the file.
 */
internal class IntelligentLanguage(
    private val base: Language,
    private val path: String,
    private val intelligence: () -> EditorIntelligence?,
) : Language {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val provider = intelligence()
        val result = provider?.let {
            runCatching { it.completion(path, content.reference.toString(), Position(position.line, position.column)) }.getOrNull()
        }
        if (result == null) {
            base.requireAutoComplete(content, position, publisher, extraArguments)
            return
        }
        publisher.checkCancelled()
        val line = content.getLine(position.line)
        var start = position.column
        while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) start--
        val prefix = line.substring(start, position.column)
        publisher.setComparator(compareBy<CompletionItem> { it.sortText ?: "" }.thenBy { it.label.toString() })
        publisher.addItems(result.items.map { LspEditorCompletionItem(it, prefix.length) })
    }

    override fun getAnalyzeManager(): AnalyzeManager = base.analyzeManager
    override fun getInterruptionLevel(): Int = base.interruptionLevel
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int = base.getIndentAdvance(content, line, column)
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int, spaceCountOnLine: Int, tabCountOnLine: Int): Int =
        base.getIndentAdvance(content, line, column, spaceCountOnLine, tabCountOnLine)
    override fun useTab(): Boolean = base.useTab()
    override fun getFormatter(): Formatter = base.formatter
    override fun getSymbolPairs(): SymbolPairMatch = base.symbolPairs
    override fun getNewlineHandlers(): Array<NewlineHandler>? = base.newlineHandlers
    override fun getQuickQuoteHandler(): QuickQuoteHandler? = base.quickQuoteHandler
    override fun destroy() = base.destroy()
}

/** A completion from the language server; applies its text edit (and #include insertions). */
internal class LspEditorCompletionItem(
    private val item: LspCompletionItem,
    prefixLength: Int,
) : CompletionItem(item.label, item.detail ?: "", SimpleCompletionIconDrawer.draw(kindOf(item.kind))) {

    init {
        this.prefixLength = prefixLength
        kind = kindOf(item.kind)
        sortText = item.sortText
        filterText = item.filterText
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        val edit = item.textEdit
        text.beginBatchEdit()
        try {
            if (edit != null) {
                val start = clamp(text, edit.range.start)
                // The user may have typed after the request: replace up to the caret.
                val endIndex = maxOf(text.getCharIndex(clamp(text, edit.range.end).first, clamp(text, edit.range.end).second), text.getCharIndex(line, column))
                val end = text.indexer.getCharPosition(endIndex)
                text.replace(start.first, start.second, end.line, end.column, edit.newText)
            } else {
                val insert = item.insertText ?: item.label
                text.replace(line, column - prefixLength, line, column, insert)
            }
            // Extra edits (an #include at the top) come before the completion; apply bottom-up.
            item.additionalTextEdits.sortedWith(compareByDescending<TextEdit> { it.range.start.line }.thenByDescending { it.range.start.character })
                .forEach { extra ->
                    val s = clamp(text, extra.range.start)
                    val e = clamp(text, extra.range.end)
                    text.replace(s.first, s.second, e.first, e.second, extra.newText)
                }
        } finally {
            text.endBatchEdit()
        }
    }

    private fun clamp(text: Content, position: Position): Pair<Int, Int> {
        val line = position.line.coerceIn(0, text.lineCount - 1)
        return line to position.character.coerceIn(0, text.getColumnCount(line))
    }

    private companion object {
        fun kindOf(kind: CompletionKind): CompletionItemKind = when (kind) {
            CompletionKind.METHOD -> CompletionItemKind.Method
            CompletionKind.FUNCTION -> CompletionItemKind.Function
            CompletionKind.CONSTRUCTOR -> CompletionItemKind.Constructor
            CompletionKind.FIELD -> CompletionItemKind.Field
            CompletionKind.VARIABLE -> CompletionItemKind.Variable
            CompletionKind.CLASS -> CompletionItemKind.Class
            CompletionKind.INTERFACE -> CompletionItemKind.Interface
            CompletionKind.MODULE -> CompletionItemKind.Module
            CompletionKind.PROPERTY -> CompletionItemKind.Property
            CompletionKind.ENUM -> CompletionItemKind.Enum
            CompletionKind.ENUM_MEMBER -> CompletionItemKind.EnumMember
            CompletionKind.KEYWORD -> CompletionItemKind.Keyword
            CompletionKind.SNIPPET -> CompletionItemKind.Snippet
            CompletionKind.CONSTANT -> CompletionItemKind.Constant
            CompletionKind.STRUCT -> CompletionItemKind.Struct
            CompletionKind.FILE -> CompletionItemKind.File
            CompletionKind.FOLDER -> CompletionItemKind.Folder
            CompletionKind.OPERATOR -> CompletionItemKind.Operator
            CompletionKind.TYPE_PARAMETER -> CompletionItemKind.TypeParameter
            CompletionKind.VALUE -> CompletionItemKind.Value
            else -> CompletionItemKind.Text
        }
    }
}
