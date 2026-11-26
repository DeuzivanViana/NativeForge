package io.nativeforge.feature.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorFontSizeTest {
    @Test
    fun `a pinch snaps to the nearest whole size`() {
        assertEquals(15, EditorFontSize.snap(14.6f))
        assertEquals(14, EditorFontSize.snap(14.4f))
        assertEquals(13, EditorFontSize.snap(13f))
    }

    @Test
    fun `sizes stay inside the range Settings offers`() {
        assertEquals(EditorFontSize.RANGE.first, EditorFontSize.snap(3f))
        assertEquals(EditorFontSize.RANGE.last, EditorFontSize.snap(80f))
        assertEquals(EditorFontSize.DEFAULT, EditorFontSize.snap(Float.NaN))
    }

    @Test
    fun `keyboard zoom moves one whole step and stops at the ends`() {
        assertEquals(14, EditorFontSize.step(13f, 1))
        assertEquals(12, EditorFontSize.step(13f, -1))
        // A fractional size from an older version first lands on a whole step.
        assertEquals(15, EditorFontSize.step(13.7f, 1))
        assertEquals(EditorFontSize.RANGE.last, EditorFontSize.step(EditorFontSize.RANGE.last.toFloat(), 1))
        assertEquals(EditorFontSize.RANGE.first, EditorFontSize.step(EditorFontSize.RANGE.first.toFloat(), -1))
    }
}
