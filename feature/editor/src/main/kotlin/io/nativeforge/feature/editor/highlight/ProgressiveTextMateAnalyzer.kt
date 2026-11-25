package io.nativeforge.feature.editor.highlight

import android.util.Log
import io.github.rosemoe.sora.lang.analysis.SequenceUpdateRange
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.OnlineBracketsMatcher
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.util.BaseAnalyzeManager
import io.github.rosemoe.sora.langs.textmate.MyState
import io.github.rosemoe.sora.langs.textmate.TextMateAnalyzer
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.folding.IndentRange
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * TextMate highlighting that colors the screen first, replacing sora's analyzer for a
 * [TextMateLanguage] (which tokenizes the whole file before it shows any color).
 *
 * Tokenizing still uses sora's [TextMateAnalyzer.tokenizeLine] (same colors, same word
 * completion); what changes is the order and the publishing, see [ProgressiveHighlighter].
 * A worker thread owns a copy of the text, as in sora, and applies the edits in order.
 */
internal class ProgressiveTextMateAnalyzer(
    private val language: TextMateLanguage,
    private val mode: HighlightMode,
) : BaseAnalyzeManager() {

    private val delegate = language.analyzeManager as TextMateAnalyzer
    private val folding = language.languageConfiguration?.folding
    private val brackets = language.languageConfiguration?.brackets
        ?.filter { it.open.length == 1 && it.close.length == 1 }
        ?.takeIf { it.isNotEmpty() }
        ?.let { pairs -> OnlineBracketsMatcher(CharArray(pairs.size * 2) { i -> if (i % 2 == 0) pairs[i / 2].open[0] else pairs[i / 2].close[0] }, 100_000) }

    @Volatile private var worker: Worker? = null
    @Volatile private var viewport = 0 to INITIAL_VIEW_LINES

    /** The lines on screen (from the editor's scroll events); they are colored first. */
    fun setViewport(first: Int, last: Int) {
        if (last < first || viewport == (first to last)) return
        viewport = first to last
        worker?.offer(Command.Viewport(first, last))
    }

    override fun rerun() {
        worker?.abort()
        worker = null
        val text = contentRef?.reference ?: return
        // A private copy: the worker reads it while the user keeps typing in the original.
        val copy = text.copyText(false).apply { isUndoEnabled = false }
        worker = Worker(copy).also { w ->
            val (first, last) = viewport
            w.offer(Command.Viewport(first, last))
            w.start()
        }
    }

    override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
        worker?.offer(Command.Insert(start.line, start.column, end.line, insertedContent.toString()))
    }

    override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
        worker?.offer(Command.Delete(start.line, start.column, end.line, end.column))
    }

    override fun destroy() {
        worker?.abort()
        worker = null
        delegate.destroy()
        super.destroy()
    }

    private sealed interface Command {
        class Insert(val line: Int, val column: Int, val endLine: Int, val text: String) : Command
        class Delete(val line: Int, val column: Int, val endLine: Int, val endColumn: Int) : Command
        class Viewport(val first: Int, val last: Int) : Command
    }

    private inner class Worker(private val text: Content) : Thread("Highlighter-${ids.incrementAndGet()}") {
        private val queue = LinkedBlockingQueue<Command>()
        @Volatile private var aborted = false
        private val spans = LineSpans()
        private val styles = Styles(spans)
        private val highlighter = ProgressiveHighlighter(TextMateTokenizer(), spans, fullPass = mode == HighlightMode.FULL)
        private var blocksStale = true

        init {
            isDaemon = true
        }

        fun offer(command: Command) {
            queue.offer(command)
        }

        fun abort() {
            aborted = true
            interrupt()
        }

        override fun run() {
            try {
                highlighter.reset(text.lineCount)
                highlighter.takeDirty()
                publish { it.setStyles(this@ProgressiveTextMateAnalyzer, styles) }
                brackets?.let { b -> publish { it.updateBracketProvider(this@ProgressiveTextMateAnalyzer, b) } }
                while (!aborted) {
                    val busy = highlighter.hasWork() || needsBlocks()
                    var command: Command? = if (busy) queue.poll() else queue.take()
                    while (command != null && !aborted) {
                        apply(command)
                        command = queue.poll()
                    }
                    if (aborted) break
                    when {
                        highlighter.hasWork() -> {
                            val changed = highlighter.step(System.nanoTime() + STEP_NANOS)
                            if (changed != null) publishRange(changed)
                        }
                        needsBlocks() -> computeBlocks()
                    }
                }
            } catch (_: InterruptedException) {
                // Aborted.
            } catch (e: Exception) {
                Log.w(TAG, "Highlighting stopped", e)
            }
        }

        private fun apply(command: Command) {
            when (command) {
                is Command.Insert -> {
                    text.insert(command.line, command.column, command.text)
                    highlighter.onInsert(command.line, command.endLine - command.line)
                    blocksStale = true
                }
                is Command.Delete -> {
                    text.delete(command.line, command.column, command.endLine, command.endColumn)
                    highlighter.onDelete(command.line, command.endLine - command.line)
                    blocksStale = true
                }
                is Command.Viewport -> highlighter.setViewport(command.first, command.last)
            }
            highlighter.takeDirty()?.let(::publishRange)
        }

        private fun needsBlocks() = blocksStale && folding != null && mode == HighlightMode.FULL && highlighter.isComplete

        /** Indentation guides and folding regions, once every line is exact (as sora does). */
        private fun computeBlocks() {
            blocksStale = false
            val rules = folding ?: return
            val tabSize = language.tabSize
            val blocks = ArrayList<CodeBlock>()
            val ranges = IndentFolding.ranges(
                lineCount = text.lineCount,
                offSide = rules.offSide,
                indent = { line -> IndentRange.computeIndentLevel(text.getLine(line).backingCharArray, text.getColumnCount(line), tabSize) },
                marker = { line ->
                    val value = text.getLineString(line)
                    when {
                        rules.markersStart?.matchesPartially(value) == true -> IndentFolding.Marker.START
                        rules.markersEnd?.matchesPartially(value) == true -> IndentFolding.Marker.END
                        else -> IndentFolding.Marker.NONE
                    }
                },
            )
            for ((start, end) in ranges.take(MAX_BLOCKS)) {
                if (aborted || !queue.isEmpty()) {
                    blocksStale = true
                    return
                }
                val column = IndentRange.computeStartColumn(text.getLine(start).backingCharArray, text.getColumnCount(start), tabSize)
                blocks += CodeBlock().apply {
                    startLine = start
                    endLine = end
                    startColumn = column
                    endColumn = column
                    toBottomOfEndLine = true
                }
            }
            blocks.sortWith(CodeBlock.COMPARATOR_END)
            styles.blocks = blocks
            styles.setIndentCountMode(true)
            styles.finishBuilding()
            publish { it.setStyles(this@ProgressiveTextMateAnalyzer, styles) }
        }

        private fun publishRange(range: IntRange) {
            publish { it.updateStyles(this@ProgressiveTextMateAnalyzer, styles, SequenceUpdateRange(range.first, range.last)) }
        }

        private inline fun publish(action: (StyleReceiver) -> Unit) {
            if (aborted || worker !== this) return
            receiver?.let(action)
        }

        /** Sora's TextMate tokenizer on the worker's copy of the text. */
        private inner class TextMateTokenizer : LineTokenizer<MyState, List<Span>> {
            override val initialState: MyState? = null

            override fun tokenize(line: Int, state: MyState?): Tokenized<MyState, List<Span>> {
                val content = text.getLine(line)
                if (content.length > MAX_TOKENIZED_LINE) {
                    // Minified or generated lines: tokenizing them could take seconds each.
                    return Tokenized(state, listOf(plainSpan()))
                }
                val result = delegate.tokenizeLine(content, state, line)
                return Tokenized(result.state, result.spans ?: listOf(plainSpan()))
            }

            override fun stateEquals(a: MyState?, b: MyState?): Boolean = delegate.stateEquals(a, b)
            override fun onStateAdded(state: MyState) = delegate.onAddState(state)
            override fun onStateRemoved(state: MyState) = delegate.onAbandonState(state)
        }
    }

    private companion object {
        const val TAG = "Highlighter"
        val ids = AtomicInteger()

        /** Before the first scroll event: the top of the file. */
        const val INITIAL_VIEW_LINES = 60

        /** Work between two publications; keeps the screen updating while a long pass runs. */
        const val STEP_NANOS = 24_000_000L

        /** Lines longer than this are shown without colors (VS Code stops at 20 000 characters). */
        const val MAX_TOKENIZED_LINE = 10_000

        const val MAX_BLOCKS = 0xFFFF
    }
}

/** Spans per line, written by the worker and read by the editor while it draws. */
internal class LineSpans : Spans, LineSpanStore<List<Span>> {
    private val lines = ArrayList<List<Span>?>()

    private fun get(line: Int): List<Span>? = synchronized(lines) { lines.getOrNull(line) }

    override fun set(line: Int, spans: List<Span>?) {
        synchronized(lines) { if (line in lines.indices) lines[line] = spans }
    }

    override fun insert(line: Int, count: Int) {
        synchronized(lines) { lines.addAll(line.coerceIn(0, lines.size), List(count) { null }) }
    }

    override fun remove(line: Int, count: Int) {
        synchronized(lines) {
            if (line in lines.indices) lines.subList(line, (line + count).coerceAtMost(lines.size)).clear()
        }
    }

    override fun clear(lineCount: Int) {
        synchronized(lines) {
            lines.clear()
            lines.addAll(List(lineCount) { null })
        }
    }

    override fun adjustOnInsert(start: CharPosition, end: CharPosition) = Unit
    override fun adjustOnDelete(start: CharPosition, end: CharPosition) = Unit
    override fun getLineCount(): Int = synchronized(lines) { lines.size }
    override fun supportsModify(): Boolean = false
    override fun modify(): Spans.Modifier = throw UnsupportedOperationException("Spans are written by the highlighter")

    override fun read(): Spans.Reader = object : Spans.Reader {
        private val plain = plainSpan()
        private var current: List<Span>? = null

        override fun moveToLine(line: Int) {
            current = get(line)
        }

        override fun getSpanCount(): Int = current?.size ?: 1
        override fun getSpanAt(index: Int): Span = current?.get(index) ?: plain
        override fun getSpansOnLine(line: Int): List<Span> = get(line) ?: listOf(plainSpan())
    }
}

private fun plainSpan(): Span = SpanFactory.obtainNoExt(0, EditorColorScheme.TEXT_NORMAL.toLong())
