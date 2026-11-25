package io.nativeforge.feature.editor.highlight

/** How much highlighting a file gets, by size. */
enum class HighlightMode {
    /** Exact colors for the whole file, analyzed in the background (screen first). */
    FULL,

    /** Large file: only the lines around the screen are analyzed; no word completion or block lines. */
    VIEWPORT,

    /** Huge file: plain text. */
    OFF;

    companion object {
        const val VIEWPORT_CHARS = 1_000_000
        const val VIEWPORT_LINES = 20_000
        const val OFF_CHARS = 8_000_000

        fun forSize(chars: Int, lines: Int): HighlightMode = when {
            chars > OFF_CHARS -> OFF
            chars > VIEWPORT_CHARS || lines > VIEWPORT_LINES -> VIEWPORT
            else -> FULL
        }
    }
}
