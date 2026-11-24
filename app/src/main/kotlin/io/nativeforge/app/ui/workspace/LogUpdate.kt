package io.nativeforge.app.ui.workspace

/**
 * How to turn the log text currently shown into the new one without rebuilding it, so that a
 * text selection the user is dragging survives lines being appended or trimmed while a build runs.
 *
 * The shown text is the lines joined with `\n`. [Incremental] means: delete the first [dropLines]
 * lines (and the newline after them), then append [appended] at the end. [Replace] means the new
 * log is unrelated (new build, cleared log) and the text must be rebuilt.
 */
sealed interface LogUpdate {
    data object None : LogUpdate
    data object Replace : LogUpdate
    data class Incremental(val dropLines: Int, val appended: List<LogLine>) : LogUpdate
}

/**
 * Compares by identity: [WorkspaceViewModel] builds each new log as `(old + line).takeLast(max)`,
 * so lines kept from the previous list are the same instances.
 */
fun planLogUpdate(shown: List<LogLine>, next: List<LogLine>): LogUpdate {
    if (shown === next) return LogUpdate.None
    if (shown.isEmpty()) return if (next.isEmpty()) LogUpdate.None else LogUpdate.Replace
    if (next.isEmpty()) return LogUpdate.Replace
    val drop = shown.indexOfFirst { it === next.first() }
    if (drop < 0) return LogUpdate.Replace
    val kept = shown.size - drop
    if (kept > next.size) return LogUpdate.Replace
    for (i in 0 until kept) if (shown[drop + i] !== next[i]) return LogUpdate.Replace
    val appended = next.subList(kept, next.size)
    if (drop == 0 && appended.isEmpty()) return LogUpdate.None
    return LogUpdate.Incremental(drop, appended.toList())
}

/** Number of characters taken by the first [lines] lines of [shown] plus the newline after them. */
fun charsOfFirstLines(shown: List<LogLine>, lines: Int): Int {
    var total = 0
    for (i in 0 until lines) total += shown[i].text.length + 1
    return total
}
