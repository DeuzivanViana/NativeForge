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

/**
 * [log] with [batch] appended, keeping the last [max] lines. Lines kept from [log] stay the same
 * instances, which [planLogUpdate] relies on to update the shown text in place.
 */
fun appendCapped(log: List<LogLine>, batch: List<LogLine>, max: Int): List<LogLine> {
    if (batch.isEmpty()) return log
    val total = log.size + batch.size
    if (total <= max) return log + batch
    val keepOld = (max - batch.size).coerceAtLeast(0)
    val result = ArrayList<LogLine>(max)
    if (keepOld > 0) result.addAll(log.subList(log.size - keepOld, log.size))
    result.addAll(if (batch.size > max) batch.subList(batch.size - max, batch.size) else batch)
    return result
}

/**
 * One output line as shown: a program printing megabytes without a newline would otherwise make
 * one huge line that the text view lays out again on every update.
 */
fun logLine(text: String, isError: Boolean, maxChars: Int = MAX_LOG_LINE_CHARS): LogLine =
    LogLine(if (text.length <= maxChars) text else text.take(maxChars) + " … (${text.length - maxChars} more characters)", isError)

const val MAX_LOG_LINE_CHARS = 4_000
