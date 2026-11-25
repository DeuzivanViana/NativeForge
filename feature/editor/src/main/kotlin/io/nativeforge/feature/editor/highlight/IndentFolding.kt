package io.nativeforge.feature.editor.highlight

/**
 * Indentation-based code blocks (the guide lines and folding regions), as VS Code computes them.
 * A port of sora-editor's `IndentRange.computeRanges`, which can only run inside sora's own
 * analyzer thread.
 */
internal object IndentFolding {
    /** A folding marker on a line, such as `#pragma region` / `#pragma endregion`. */
    enum class Marker { NONE, START, END }

    /**
     * Regions as (start line, end line) pairs. [indent] returns a line's indentation level, or
     * -1 for a blank line; in [offSide] languages blank lines belong to the block above.
     */
    fun ranges(lineCount: Int, offSide: Boolean, indent: (Int) -> Int, marker: (Int) -> Marker): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        val previousRegions = ArrayList<Region>()
        // Sentinel, so there is always one entry.
        previousRegions += Region(-1, lineCount + 1, lineCount + 1)
        for (line in lineCount - 1 downTo 0) {
            val level = indent(line)
            var previous = previousRegions.last()
            if (level == -1) {
                if (offSide) previous.endAbove = line
                continue
            }
            when (marker(line)) {
                Marker.START -> {
                    var i = previousRegions.size - 1
                    while (i > 0 && previousRegions[i].indent != -2) i--
                    if (i > 0) {
                        previous = previousRegions[i]
                        result += line to previous.line
                        previous.line = line
                        previous.indent = level
                        previous.endAbove = line
                        continue
                    }
                    // No end marker below: a regular line.
                }
                Marker.END -> {
                    previousRegions += Region(-2, line, line)
                    continue
                }
                Marker.NONE -> Unit
            }
            if (previous.indent > level) {
                do {
                    previousRegions.removeAt(previousRegions.size - 1)
                    previous = previousRegions.last()
                } while (previous.indent > level)
                val endLine = previous.endAbove - 1
                if (endLine - line >= 1) result += line to endLine
            }
            if (previous.indent == level) {
                previous.endAbove = line
            } else {
                previousRegions += Region(level, line, line)
            }
        }
        return result
    }

    private class Region(var indent: Int, var endAbove: Int, var line: Int)
}
