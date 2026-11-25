package io.nativeforge.feature.editor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightModeTest {
    @Test
    fun modeFollowsTheFileSize() {
        assertEquals(HighlightMode.FULL, HighlightMode.forSize(chars = 300_000, lines = 8_000))
        assertEquals(HighlightMode.VIEWPORT, HighlightMode.forSize(chars = 900_000, lines = 25_000))
        assertEquals(HighlightMode.VIEWPORT, HighlightMode.forSize(chars = 2_000_000, lines = 10))
        assertEquals(HighlightMode.OFF, HighlightMode.forSize(chars = 9_000_000, lines = 200_000))
    }
}
