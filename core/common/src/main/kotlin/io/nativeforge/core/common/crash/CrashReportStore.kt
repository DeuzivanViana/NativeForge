package io.nativeforge.core.common.crash

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What ended the app. [label] is shown to the user and written into the report. */
enum class CrashKind(val id: String, val label: String) {
    /** An exception escaped a Kotlin/Java thread. */
    JVM("jvm", "Kotlin/Java exception"),

    /** A signal such as SIGSEGV or SIGABRT in native code. */
    NATIVE("native", "Native crash"),

    /** The main thread was blocked and Android closed the app. */
    ANR("anr", "App not responding"),

    /** Android ended the app while it was on screen (low memory, too much CPU, a kill signal). */
    KILLED("killed", "Closed by Android"),
    ;

    companion object {
        fun fromId(id: String): CrashKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Crash reports kept on the device, in plain text files of [directory], so that a crash can be
 * looked at and shared after the app starts again. Nothing is sent anywhere.
 *
 * Files are named `crash-<epoch millis>-<kind>.txt`. The newest [keep] reports are kept.
 * Reports newer than the last [markAllSeen] are "unseen": the app shows them once on its next
 * start.
 */
class CrashReportStore(
    val directory: File,
    private val keep: Int = DEFAULT_KEEP,
) {
    data class Report(val file: File, val time: Long, val kind: CrashKind) {
        fun text(): String = runCatching { file.readText() }.getOrDefault("")
    }

    /**
     * Where the native signal handler writes the crash it is handling. It cannot format a full
     * report from a signal handler, so [adoptNativeReport] turns it into one on the next start.
     */
    val nativePendingFile: File get() = File(directory, NATIVE_PENDING)

    private val seenFile get() = File(directory, SEEN)
    private val exitCheckFile get() = File(directory, EXIT_CHECK)

    /**
     * Writes a report and returns its file. Meant to be called from an uncaught exception
     * handler too: no locks, one small write, and the old reports are pruned afterwards.
     */
    fun write(kind: CrashKind, time: Long, details: Map<String, String>, body: String): File {
        directory.mkdirs()
        var file = File(directory, fileName(time, kind))
        var stamp = time
        while (file.exists()) file = File(directory, fileName(++stamp, kind))
        file.writeText(format(kind, time, details, body))
        runCatching { prune() }
        return file
    }

    /** All reports, newest first. */
    fun reports(): List<Report> =
        directory.listFiles().orEmpty().mapNotNull(::parse).sortedByDescending { it.time }

    /** Reports written after the last [markAllSeen], newest first. */
    fun unseen(): List<Report> {
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        return reports().filter { it.time > seen }
    }

    fun markAllSeen() {
        val newest = reports().firstOrNull()?.time ?: return
        directory.mkdirs()
        seenFile.writeText("$newest\n")
    }

    fun delete(report: Report) {
        report.file.delete()
    }

    fun clear() {
        reports().forEach(::delete)
        nativePendingFile.delete()
    }

    /** True when a report of [kind] was written within [windowMillis] of [time]. */
    fun hasReportNear(time: Long, kind: CrashKind, windowMillis: Long = 10_000): Boolean =
        reports().any { it.kind == kind && kotlin.math.abs(it.time - time) <= windowMillis }

    /**
     * Turns what the native signal handler wrote ([nativePendingFile]) into a report, with
     * [details] of the app and device, and returns it; null when there is none.
     */
    fun adoptNativeReport(details: Map<String, String>): File? {
        val pending = nativePendingFile
        if (!pending.isFile) return null
        val body = runCatching { pending.readText() }.getOrDefault("").trim()
        val time = pending.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        pending.delete()
        if (body.isEmpty()) return null
        return write(CrashKind.NATIVE, time, details, body)
    }

    /** Time of the newest process exit already looked at (see Android's ApplicationExitInfo). */
    var exitReasonsCheckedAt: Long
        get() = runCatching { exitCheckFile.readText().trim().toLong() }.getOrDefault(0L)
        set(value) {
            directory.mkdirs()
            exitCheckFile.writeText("$value\n")
        }

    private fun prune() {
        reports().drop(keep).forEach(::delete)
    }

    private fun parse(file: File): Report? {
        val match = NAME.matchEntire(file.name) ?: return null
        val kind = CrashKind.fromId(match.groupValues[2]) ?: return null
        return Report(file, match.groupValues[1].toLong(), kind)
    }

    companion object {
        const val DEFAULT_KEEP = 20
        const val NATIVE_PENDING = "native-crash.txt"
        private const val SEEN = "seen"
        private const val EXIT_CHECK = "exit-reasons-checked"
        private val NAME = Regex("""crash-(\d+)-([a-z]+)\.txt""")

        fun fileName(time: Long, kind: CrashKind) = "crash-$time-${kind.id}.txt"

        /** The text of a report: a title, `key: value` details, then the stack trace(s). */
        fun format(kind: CrashKind, time: Long, details: Map<String, String>, body: String): String = buildString {
            appendLine("NativeForge crash report: ${kind.label}")
            appendLine("Time: ${timestamp(time)}")
            details.forEach { (key, value) -> appendLine("$key: $value") }
            appendLine()
            append(body.trimEnd())
            appendLine()
        }

        /** The report of an exception that escaped [thread]. */
        fun describe(thread: String, error: Throwable): String =
            "Thread: $thread\n\n" + error.stackTraceToString()

        fun timestamp(time: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(time))
    }
}
