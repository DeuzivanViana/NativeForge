package io.nativeforge.app.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals

class LogUpdateTest {
    private fun line(text: String) = LogLine(text, isError = false)

    @Test
    fun appendingLinesIsIncremental() {
        val a = line("a"); val b = line("b"); val c = line("c")
        assertEquals(LogUpdate.Incremental(0, listOf(c)), planLogUpdate(listOf(a, b), listOf(a, b, c)))
    }

    @Test
    fun trimmingAndAppendingIsIncremental() {
        val a = line("a"); val b = line("b"); val c = line("c"); val d = line("d")
        val old = listOf(a, b, c)
        val new = (old + d).takeLast(3)
        assertEquals(LogUpdate.Incremental(1, listOf(d)), planLogUpdate(old, new))
    }

    @Test
    fun equalContentButNewInstancesIsReplace() {
        // A new build with the same output must not be merged into the old text.
        assertEquals(LogUpdate.Replace, planLogUpdate(listOf(line("a")), listOf(line("a"), line("b"))))
    }

    @Test
    fun clearedOrFirstLogIsReplace() {
        val a = line("a")
        assertEquals(LogUpdate.Replace, planLogUpdate(listOf(a), emptyList()))
        assertEquals(LogUpdate.Replace, planLogUpdate(emptyList(), listOf(a)))
        assertEquals(LogUpdate.None, planLogUpdate(emptyList(), emptyList()))
    }

    @Test
    fun sameLinesInNewListIsNone() {
        val a = line("a"); val b = line("b")
        assertEquals(LogUpdate.None, planLogUpdate(listOf(a, b), listOf(a, b)))
    }

    @Test
    fun charsOfFirstLinesCountsNewlines() {
        val lines = listOf(line("abc"), line(""), line("de"))
        assertEquals(0, charsOfFirstLines(lines, 0))
        assertEquals(4, charsOfFirstLines(lines, 1))
        assertEquals(5, charsOfFirstLines(lines, 2))
    }
}
