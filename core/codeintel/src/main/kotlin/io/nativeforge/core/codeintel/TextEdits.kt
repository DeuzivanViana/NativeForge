package io.nativeforge.core.codeintel

import io.nativeforge.core.lsp.Position
import io.nativeforge.core.lsp.TextEdit

/** Applies LSP text edits to files that are not open in an editor (rename across files). */
object TextEdits {

    fun apply(text: String, edits: List<TextEdit>): String {
        val lineStarts = lineStarts(text)
        val builder = StringBuilder(text)
        edits.map { offset(text, lineStarts, it.range.start) to (offset(text, lineStarts, it.range.end) to it.newText) }
            .sortedByDescending { it.first }
            .forEach { (start, rest) ->
                val (end, newText) = rest
                builder.replace(start, maxOf(start, end), newText)
            }
        return builder.toString()
    }

    private fun lineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, c -> if (c == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun offset(text: String, lineStarts: IntArray, position: Position): Int {
        if (position.line >= lineStarts.size) return text.length
        val start = lineStarts[position.line.coerceAtLeast(0)]
        val lineEnd = if (position.line + 1 < lineStarts.size) lineStarts[position.line + 1] - 1 else text.length
        return (start + position.character).coerceIn(start, lineEnd)
    }
}
