package io.nativeforge.feature.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalFontSizeTest {
    @Test
    fun `a small pinch keeps the size`() {
        assertFalse(TerminalFontSize.isStep(1.05f))
        assertEquals(12, TerminalFontSize.afterPinch(12, 1.05f))
        assertEquals(12, TerminalFontSize.afterPinch(12, 0.95f))
    }

    @Test
    fun `each step changes the size by one whole sp`() {
        assertTrue(TerminalFontSize.isStep(1.2f))
        assertEquals(13, TerminalFontSize.afterPinch(12, 1.2f))
        assertEquals(11, TerminalFontSize.afterPinch(12, 0.8f))
        // A fast pinch is still one step: the activity starts the next one afresh.
        assertEquals(13, TerminalFontSize.afterPinch(12, 3f))
    }

    @Test
    fun `sizes stay inside the range Settings offers`() {
        val range = ConsoleRequest.FONT_SIZES
        assertEquals(range.last, TerminalFontSize.afterPinch(range.last, 1.5f))
        assertEquals(range.first, TerminalFontSize.afterPinch(range.first, 0.5f))
    }
}
