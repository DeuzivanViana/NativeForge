package io.nativeforge.feature.editor.highlight

/** Turns one line into display spans, given the state at the end of the line before. */
internal interface LineTokenizer<S, T> {
    /** State before the first line. */
    val initialState: S?

    fun tokenize(line: Int, state: S?): Tokenized<S, T>

    fun stateEquals(a: S?, b: S?): Boolean

    /** A state became part of the line table (word completion counts its identifiers). */
    fun onStateAdded(state: S) {}

    /** A state left the line table. */
    fun onStateRemoved(state: S) {}
}

internal class Tokenized<S, T>(val state: S?, val spans: T)

/** Receives the spans of each line; shared with the UI thread, so it must be thread-safe. */
internal interface LineSpanStore<T> {
    fun set(line: Int, spans: T?)
    fun insert(line: Int, count: Int)
    fun remove(line: Int, count: Int)
    fun clear(lineCount: Int)
}

/**
 * Line-state bookkeeping for highlighting that shows colors before the whole file is analyzed.
 *
 * TextMate-style tokenizers are sequential: the state at the end of a line depends on every line
 * before it, so exact colors for line N need lines 0..N-1 first. Sora's own analyzer therefore
 * tokenizes the whole file before it shows any color, which takes seconds on a big C++ file.
 * This class instead:
 *
 * * runs the exact pass from the top in small steps ([step]), publishing each chunk, so the first
 *   screen is colored after a few lines;
 * * colors a viewport far ahead of the exact pass right away by starting a few lines above it from
 *   the initial state (a guess, as VS Code does), and replaces those colors when the exact pass
 *   arrives;
 * * after an edit, re-tokenizes from the edited line and stops as soon as a line ends in the same
 *   state as before, so typing costs a line or two;
 * * with [fullPass] off (large files) the exact pass only runs near the viewport.
 *
 * Not thread-safe: one worker thread owns it. Line text lives with the [tokenizer].
 */
internal class ProgressiveHighlighter<S, T>(
    private val tokenizer: LineTokenizer<S, T>,
    private val store: LineSpanStore<T>,
    var fullPass: Boolean = true,
) {
    private enum class Kind {
        /** Never tokenized, or its guess was invalidated by an edit. */
        NONE,

        /** Colored by a guess; no state kept. */
        GUESS,

        /** Exact once, but an edit above may have changed its state; its state can confirm the lines after it. */
        OLD,

        /**
         * Like [OLD], but the line must be tokenized again before use: its text was edited, or the
         * line above now ends in a different state than the one it was tokenized from.
         */
        EDITED,

        /** Exact: every line above is exact too (index below [frontier]). */
        EXACT,
    }

    private val states = ArrayList<S?>()
    private val kinds = ArrayList<Kind>()

    /** End states of [Kind.GUESS] lines: scrolling on continues a guess instead of starting over. */
    private val guessed = ArrayList<S?>()

    /** Whether the spans shown for a line belong to its current text (possibly with guessed colors). */
    private val fresh = ArrayList<Boolean>()

    /** Lines below this index are [Kind.EXACT]. */
    var frontier = 0
        private set

    val lineCount: Int get() = kinds.size

    /** True once every line is exact (code blocks can be computed). */
    val isComplete: Boolean get() = frontier >= lineCount

    private var viewFirst = 0
    private var viewLast = -1
    private var dirtyFirst = Int.MAX_VALUE
    private var dirtyLast = -1

    /** The exact state at the end of [line], or null when it is not known yet. */
    fun exactState(line: Int): S? = if (line < frontier) states[line] else null

    fun reset(lineCount: Int) {
        for (i in states.indices) release(i)
        states.clear(); kinds.clear(); fresh.clear(); guessed.clear()
        repeat(lineCount) { states.add(null); kinds.add(Kind.NONE); fresh.add(false); guessed.add(null) }
        frontier = 0
        store.clear(lineCount)
        markDirty(0, lineCount - 1)
    }

    /** The lines on screen; [step] colors them first. */
    fun setViewport(first: Int, last: Int) {
        viewFirst = first
        viewLast = last
    }

    /**
     * Text was inserted at [line]; it added [newLines] line breaks. Call after the text changed.
     * Slot `line + newLines` keeps the old line's state: it holds the old line's tail.
     */
    fun onInsert(line: Int, newLines: Int) {
        if (line !in 0 until lineCount) return
        if (newLines > 0) {
            for (k in 0 until newLines) {
                states.add(line, null); kinds.add(line, Kind.NONE); fresh.add(line, false); guessed.add(line, null)
            }
            store.insert(line, newLines)
            if (line < frontier) frontier += newLines
        }
        edited(line + newLines)
        demoteFrom(line)
        markDirty(line, maxOf(line + newLines, viewLast))
    }

    /**
     * Text was deleted from [line] across [removedLines] line breaks. Call after the text changed.
     * The slots of the deleted lines go; [line] keeps the state of the old last line (its tail).
     */
    fun onDelete(line: Int, removedLines: Int) {
        if (line !in 0 until lineCount) return
        if (removedLines > 0) {
            val count = removedLines.coerceAtMost(lineCount - 1 - line)
            for (i in line until line + count) release(i)
            repeat(count) { states.removeAt(line); kinds.removeAt(line); fresh.removeAt(line); guessed.removeAt(line) }
            store.remove(line, count)
            frontier = when {
                frontier > line + count -> frontier - count
                frontier > line -> line
                else -> frontier
            }
        }
        edited(line)
        demoteFrom(line)
        markDirty(line, maxOf(line, viewLast))
    }

    /** True while [step] has something to do. */
    fun hasWork(): Boolean = firstStaleInView() != null || (fullPass && frontier < lineCount)

    /**
     * Does a slice of work, at most until [deadlineNanos] ([System.nanoTime]): the viewport first,
     * then (with [fullPass]) the exact pass. Returns the lines whose spans changed, or null.
     */
    fun step(deadlineNanos: Long): IntRange? {
        val stale = firstStaleInView()
        when {
            stale != null && stale - frontier <= CATCH_UP_LINES -> advance(deadlineNanos, limit = viewEnd())
            stale != null -> guess(stale, viewEnd())
            fullPass && frontier < lineCount -> advance(deadlineNanos, limit = lineCount - 1)
        }
        return takeDirty()
    }

    /** Lines changed since the last call (edits, [reset]), for a repaint. */
    fun takeDirty(): IntRange? {
        if (dirtyLast < dirtyFirst) return null
        val range = dirtyFirst.coerceAtLeast(0)..dirtyLast.coerceAtMost(lineCount - 1)
        dirtyFirst = Int.MAX_VALUE
        dirtyLast = -1
        return if (range.isEmpty()) null else range
    }

    private fun viewEnd(): Int = (viewLast + VIEW_MARGIN_LINES).coerceAtMost(lineCount - 1)

    private fun firstStaleInView(): Int? {
        if (viewLast < 0 || lineCount == 0) return null
        val first = (viewFirst - VIEW_MARGIN_LINES).coerceIn(0, lineCount - 1).coerceAtLeast(frontier)
        for (i in first..viewEnd()) if (!fresh[i]) return i
        return null
    }

    /** Exact pass: tokenizes from [frontier], skipping ahead when a line ends as it did before an edit. */
    private fun advance(deadlineNanos: Long, limit: Int) {
        while (frontier <= limit && frontier < lineCount) {
            val line = frontier
            val previous = if (line == 0) tokenizer.initialState else states[line - 1]
            val result = tokenizer.tokenize(line, previous)
            val oldKind = kinds[line]
            val oldState = states[line]
            val sameEnd = (oldKind == Kind.OLD || oldKind == Kind.EDITED) && tokenizer.stateEquals(oldState, result.state)
            setState(line, result.state)
            kinds[line] = Kind.EXACT
            guessed[line] = null
            fresh[line] = true
            store.set(line, result.spans)
            markDirty(line, line)
            frontier++
            if (sameEnd) {
                // Lines below were exact before the edit and start in the same state again.
                while (frontier < lineCount && kinds[frontier] == Kind.OLD) {
                    kinds[frontier] = Kind.EXACT
                    frontier++
                }
            } else if (frontier < lineCount && kinds[frontier] == Kind.OLD) {
                // The next line started from the old state: its own end state can still confirm
                // the lines after it, but it must be tokenized again first.
                kinds[frontier] = Kind.EDITED
            }
            if (System.nanoTime() >= deadlineNanos) return
        }
    }

    /**
     * Colors [stale]..[end] without the exact state: starts from the nearest known (or guessed)
     * state up to [GUESS_LOOKBACK_LINES] above, or from the initial state there.
     */
    private fun guess(stale: Int, end: Int) {
        val floor = (stale - GUESS_LOOKBACK_LINES).coerceAtLeast(frontier)
        var start = floor
        var state = if (floor == frontier && floor > 0) states[floor - 1] else tokenizer.initialState
        for (j in stale - 1 downTo floor) {
            val known = when (kinds[j]) {
                Kind.OLD -> states[j]
                Kind.GUESS -> guessed[j]
                else -> null
            }
            if (known != null) {
                start = j + 1
                state = known
                break
            }
        }
        for (line in start..end) {
            val result = tokenizer.tokenize(line, state)
            state = result.state
            if (!fresh[line]) {
                store.set(line, result.spans)
                fresh[line] = true
                if (kinds[line] == Kind.NONE) kinds[line] = Kind.GUESS
                markDirty(line, line)
            }
            if (kinds[line] == Kind.GUESS) guessed[line] = state
        }
    }

    /** The text of [line] changed: its spans are stale and its state can't be reused as is. */
    private fun edited(line: Int) {
        if (line !in 0 until lineCount) return
        fresh[line] = false
        kinds[line] = when (kinds[line]) {
            Kind.EXACT, Kind.OLD, Kind.EDITED -> Kind.EDITED
            Kind.GUESS, Kind.NONE -> { guessed[line] = null; Kind.NONE }
        }
    }

    /** Lines from [line] on are no longer known to be exact. */
    private fun demoteFrom(line: Int) {
        if (line >= frontier) return
        for (i in line until frontier) if (kinds[i] == Kind.EXACT) kinds[i] = Kind.OLD
        frontier = line
    }

    private fun setState(line: Int, state: S?) {
        release(line)
        states[line] = state
        if (state != null) tokenizer.onStateAdded(state)
    }

    private fun release(line: Int) {
        val old = states[line] ?: return
        states[line] = null
        tokenizer.onStateRemoved(old)
    }

    private fun markDirty(first: Int, last: Int) {
        if (first < dirtyFirst) dirtyFirst = first
        if (last > dirtyLast) dirtyLast = last
    }

    companion object {
        /** A viewport this close below the exact pass waits for it instead of guessing. */
        const val CATCH_UP_LINES = 200

        /** Lines above and below the screen that are colored with it. */
        const val VIEW_MARGIN_LINES = 40

        /** How far above the viewport a guess starts. */
        const val GUESS_LOOKBACK_LINES = 40
    }
}
