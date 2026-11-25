package io.nativeforge.feature.editor.highlight

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressiveHighlighterTest {

    /**
     * A tiny sequential language: the state is the `{` depth times two, plus one inside a block
     * comment. A line's spans record the state it started in, so a span shows whether the line
     * was tokenized from the right state.
     */
    private class FakeTokenizer(val lines: MutableList<String>) : LineTokenizer<Int, String> {
        var calls = 0
        var live = 0
        override val initialState: Int? = null

        override fun tokenize(line: Int, state: Int?): Tokenized<Int, String> {
            calls++
            val text = lines[line]
            var comment = (state ?: 0) % 2 == 1
            var depth = (state ?: 0) / 2
            var i = 0
            while (i < text.length) {
                val pair = text.substring(i, minOf(i + 2, text.length))
                when {
                    comment && pair == "*/" -> { comment = false; i++ }
                    !comment && pair == "/*" -> { comment = true; i++ }
                    !comment && text[i] == '{' -> depth++
                    !comment && text[i] == '}' -> depth = maxOf(0, depth - 1)
                }
                i++
            }
            return Tokenized(depth * 2 + if (comment) 1 else 0, "${state ?: 0}|$text")
        }

        override fun stateEquals(a: Int?, b: Int?) = (a ?: 0) == (b ?: 0)
        override fun onStateAdded(state: Int) { live++ }
        override fun onStateRemoved(state: Int) { live-- }

        /** What a plain top-to-bottom pass produces. */
        fun reference(): List<String> {
            var state: Int? = null
            return lines.indices.map { line -> tokenize(line, state).also { state = it.state }.spans }
        }
    }

    private class FakeStore : LineSpanStore<String> {
        val lines = ArrayList<String?>()
        override fun set(line: Int, spans: String?) { lines[line] = spans }
        override fun insert(line: Int, count: Int) { lines.addAll(line, List(count) { null }) }
        override fun remove(line: Int, count: Int) { repeat(count) { lines.removeAt(line) } }
        override fun clear(lineCount: Int) { lines.clear(); lines.addAll(List(lineCount) { null }) }
    }

    private class Fixture(text: List<String>, fullPass: Boolean = true) {
        val lines = text.toMutableList()
        val tokenizer = FakeTokenizer(lines)
        val store = FakeStore()
        val highlighter = ProgressiveHighlighter(tokenizer, store, fullPass).apply { reset(lines.size) }

        /** One unit of work per call: the deadline has always passed, so the exact pass does one line. */
        fun step() = highlighter.step(deadlineNanos = 0)

        fun runToIdle(limit: Int = 1_000_000) {
            var n = 0
            while (highlighter.hasWork()) {
                step()
                check(++n < limit) { "no progress" }
            }
        }

        /** Inserts [text] at [line]:[column] like the editor does, then tells the highlighter. */
        fun insert(line: Int, column: Int, text: String) {
            val current = lines[line]
            val parts = (current.substring(0, column) + text + current.substring(column)).split('\n')
            lines[line] = parts[0]
            lines.addAll(line + 1, parts.drop(1))
            highlighter.onInsert(line, parts.size - 1)
        }

        fun delete(line: Int, column: Int, endLine: Int, endColumn: Int) {
            val merged = lines[line].substring(0, column) + lines[endLine].substring(endColumn)
            repeat(endLine - line) { lines.removeAt(line + 1) }
            lines[line] = merged
            highlighter.onDelete(line, endLine - line)
        }

        fun assertExact() {
            assertTrue(highlighter.isComplete)
            assertEquals(tokenizer.reference(), store.lines.toList())
        }
    }

    private fun code(lines: Int) = List(lines) { i ->
        when (i % 7) {
            0 -> "int f$i(void) {"
            1 -> "    /* note"
            2 -> "       more */ int x = $i;"
            3 -> "    if (x) { x--; }"
            4 -> "    return x;"
            5 -> "}"
            else -> ""
        }
    }

    @Test
    fun theFirstScreenIsColoredWithoutTheRestOfTheFile() {
        val f = Fixture(code(10_000))
        f.highlighter.setViewport(0, 50)
        // The visible lines plus the margin below them, one line per step.
        repeat(50 + ProgressiveHighlighter.VIEW_MARGIN_LINES + 1) { f.step() }
        assertEquals(91, f.tokenizer.calls)
        assertTrue((0..50).all { f.store.lines[it] != null })
        assertNull(f.store.lines[5_000])
        assertTrue(f.highlighter.hasWork(), "the background pass continues")
        f.runToIdle()
        f.assertExact()
    }

    @Test
    fun aViewportFarAheadIsGuessedThenCorrected() {
        // A comment opened at the top stays open: a guess near the bottom can't know it.
        val text = listOf("/* license") + List(9_000) { "text $it" } + listOf("*/") + code(1_000)
        val f = Fixture(text)
        f.highlighter.setViewport(5_000, 5_030)
        val changed = assertNotNull(f.step())
        assertTrue(5_000 in changed && 5_030 in changed)
        assertTrue(f.tokenizer.calls < 200, "a guess costs a screen, not the file (${f.tokenizer.calls})")
        assertEquals("0|text 4999", f.store.lines[5_000], "guessed from the initial state")
        f.runToIdle()
        assertEquals("1|text 4999", f.store.lines[5_000])
        f.assertExact()
    }

    @Test
    fun anEditThatKeepsTheStateRetokenizesOneLine() {
        val f = Fixture(code(5_000))
        f.runToIdle()
        f.tokenizer.calls = 0
        f.insert(2_500, 0, "x")
        f.highlighter.setViewport(2_480, 2_520)
        f.runToIdle()
        assertEquals(1, f.tokenizer.calls)
        f.assertExact()
    }

    @Test
    fun openingACommentRecolorsTheLinesBelow() {
        val f = Fixture(code(700))
        f.highlighter.setViewport(0, 40)
        f.runToIdle()
        f.insert(10, 0, "/* ")
        f.runToIdle()
        f.assertExact()
        f.delete(10, 0, 10, 3)
        f.runToIdle()
        f.assertExact()
    }

    @Test
    fun randomEditsEndExact() {
        val random = Random(42)
        val snippets = listOf("{", "}", "/*", "*/", "x", "\n", "\n{\n", "a */ b /* c", "\n\n")
        repeat(20) { round ->
            val f = Fixture(code(400 + round * 37))
            repeat(150) {
                when (random.nextInt(5)) {
                    0, 1 -> {
                        val line = random.nextInt(f.lines.size)
                        f.insert(line, random.nextInt(f.lines[line].length + 1), snippets.random(random))
                    }
                    2 -> {
                        val line = random.nextInt(f.lines.size)
                        val endLine = (line + random.nextInt(3)).coerceAtMost(f.lines.size - 1)
                        val column = random.nextInt(f.lines[line].length + 1)
                        val endColumn = if (endLine == line) random.nextInt(column, f.lines[line].length + 1) else random.nextInt(f.lines[endLine].length + 1)
                        f.delete(line, column, endLine, endColumn)
                    }
                    3 -> {
                        val first = random.nextInt(f.lines.size)
                        f.highlighter.setViewport(first, first + 30)
                    }
                    else -> repeat(random.nextInt(60)) { f.step() }
                }
            }
            f.runToIdle()
            f.assertExact()
            assertEquals(f.lines.size, f.tokenizer.live, "every line keeps exactly one state")
        }
    }

    @Test
    fun withoutTheFullPassTheScreenAlwaysShowsItsCurrentText() {
        val random = Random(3)
        val f = Fixture(code(20_000), fullPass = false)
        repeat(300) {
            val first = random.nextInt(f.lines.size - 40)
            f.highlighter.setViewport(first, first + 30)
            val line = (first + random.nextInt(30)).coerceAtMost(f.lines.size - 1)
            when (random.nextInt(3)) {
                0 -> f.insert(line, 0, listOf("x", "/*", "*/", "\n{", "}\n").random(random))
                1 -> f.delete(line, 0, (line + 1).coerceAtMost(f.lines.size - 1), 0)
                else -> Unit
            }
            f.runToIdle()
            for (i in first..first + 30) {
                assertEquals(f.lines[i], f.store.lines[i]?.substringAfter('|'), "line $i")
            }
        }
    }

    @Test
    fun withoutTheFullPassOnlyTheScreenIsTokenized() {
        val f = Fixture(code(50_000), fullPass = false)
        f.highlighter.setViewport(30_000, 30_040)
        f.runToIdle()
        assertTrue(f.tokenizer.calls < 200, "${f.tokenizer.calls} lines tokenized")
        assertNotNull(f.store.lines[30_020])
        assertNull(f.store.lines[10])
        assertFalse(f.highlighter.isComplete)
        // Scrolling a little continues from what is known.
        f.highlighter.setViewport(30_060, 30_100)
        f.runToIdle()
        assertNotNull(f.store.lines[30_100])
        assertTrue(f.tokenizer.calls < 400)
    }

    @Test
    fun editsReportTheLinesToRepaint() {
        val f = Fixture(code(100))
        f.highlighter.setViewport(0, 20)
        f.runToIdle()
        f.highlighter.takeDirty()
        f.insert(5, 0, "a\nb")
        val dirty = assertNotNull(f.highlighter.takeDirty())
        assertTrue(5 in dirty && 6 in dirty && 20 in dirty)
        assertNull(f.highlighter.takeDirty())
    }
}
