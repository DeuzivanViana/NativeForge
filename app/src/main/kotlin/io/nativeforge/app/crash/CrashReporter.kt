package io.nativeforge.app.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import io.nativeforge.app.BuildConfig
import io.nativeforge.core.common.crash.CrashKind
import io.nativeforge.core.common.crash.CrashReportStore
import io.nativeforge.core.common.crash.TombstoneDecoder
import io.nativeforge.feature.runner.crash.NativeCrashHandler
import java.io.File

/**
 * Keeps a report of every crash of the IDE on the device (Settings › Crash reports), and shows
 * new ones when the app starts again, so a crash can be understood and shared. Nothing leaves
 * the phone unless the user shares a report.
 *
 * Three sources:
 * - Kotlin/Java: an uncaught exception handler writes the stack trace, then lets Android end
 *   the app as before.
 * - Native code: [NativeCrashHandler] writes the signal and backtrace from a signal handler.
 * - Android 11+: the system's record of how the process ended (ApplicationExitInfo), read on
 *   the next start. It adds native crashes with the system tombstone (Android 12+), "app not
 *   responding" with all thread stacks, and kills for low memory while the app was on screen.
 */
object CrashReporter {

    @Volatile private var store: CrashReportStore? = null

    fun store(context: Context): CrashReportStore =
        store ?: CrashReportStore(File(context.applicationContext.filesDir, "crashes")).also { store = it }

    /** Called from [Application.onCreate]. Only the IDE process records crashes. */
    fun install(app: Application) {
        if (!isMainProcess(app)) return
        val store = store(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                store.write(CrashKind.JVM, System.currentTimeMillis(), details(app), CrashReportStore.describe(thread.name, error))
            }
            if (previous != null) previous.uncaughtException(thread, error) else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
        NativeCrashHandler.install(store.nativePendingFile)
    }

    /**
     * Collects what the last runs left behind (the native handler's file, Android's exit
     * records) and returns the reports the user has not seen yet. Blocking: call off the main
     * thread.
     */
    fun collectUnseen(context: Context): List<CrashReportStore.Report> {
        val store = store(context)
        runCatching { store.adoptNativeReport(details(context)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { readExitReasons(context, store) }
        return store.unseen()
    }

    /** Opens the share sheet with [report] as text and as an attached file. */
    fun share(context: Context, report: CrashReportStore.Report) {
        val text = report.text()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "NativeForge crash report (${BuildConfig.VERSION_NAME})")
            // Binder transactions are limited to about 1 MB; the attachment has everything.
            putExtra(Intent.EXTRA_TEXT, if (text.length > SHARE_TEXT_LIMIT) text.take(SHARE_TEXT_LIMIT) + "\n… (see the attached file)" else text)
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", report.file)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(Intent.createChooser(intent, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** App and device facts written at the top of every report. */
    fun details(context: Context): Map<String, String> = linkedMapOf(
        "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}",
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "ABI" to Build.SUPPORTED_ABIS.joinToString(),
        "Process" to processName(context),
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitReasons(context: Context, store: CrashReportStore) {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
        val checkedAt = store.exitReasonsCheckedAt
        // First start with this feature: look back a few days, so the crash that made the user
        // update shows up too.
        val since = if (checkedAt > 0) checkedAt else System.currentTimeMillis() - FIRST_LOOK_BACK_MS
        val newest = exits.maxOfOrNull { it.timestamp } ?: checkedAt
        for (exit in exits.filter { it.timestamp > since && it.processName == context.packageName }.sortedBy { it.timestamp }) {
            val kind = kindOf(exit) ?: continue
            // Our own handlers already wrote these, with more detail than Android keeps.
            if (kind == CrashKind.JVM && store.hasReportNear(exit.timestamp, CrashKind.JVM)) continue
            if (kind == CrashKind.NATIVE && store.hasReportNear(exit.timestamp, CrashKind.NATIVE) && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) continue
            store.write(kind, exit.timestamp, details(context) + exitDetails(exit), exitBody(exit, kind))
        }
        if (newest > checkedAt) store.exitReasonsCheckedAt = newest
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun kindOf(exit: ApplicationExitInfo): CrashKind? = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH -> CrashKind.JVM
        ApplicationExitInfo.REASON_CRASH_NATIVE -> CrashKind.NATIVE
        ApplicationExitInfo.REASON_ANR -> CrashKind.ANR
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        -> if (exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) CrashKind.KILLED else null
        else -> null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitDetails(exit: ApplicationExitInfo): Map<String, String> = linkedMapOf(
        "Exit reason" to reasonName(exit.reason),
        "Exit status" to exit.status.toString(),
        "Exit description" to (exit.description ?: "-"),
        "Memory (PSS / RSS)" to "${exit.pss / 1024} MB / ${exit.rss / 1024} MB",
        "Was on screen" to (exit.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE).toString(),
        "Source" to "Android's exit record (ApplicationExitInfo)",
    )

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitBody(exit: ApplicationExitInfo, kind: CrashKind): String {
        val trace = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
        return when {
            trace == null || trace.isEmpty() -> when (kind) {
                CrashKind.JVM -> "Android recorded a Kotlin/Java crash at this time but keeps no stack trace for it, and the app's own handler wrote none (the crash happened before this version, or the report could not be written)."
                CrashKind.NATIVE -> "Native crash, signal ${exit.status}. Android kept no tombstone for it."
                CrashKind.KILLED -> "Android ended the app while it was on screen: ${reasonName(exit.reason)}. Usually this means the phone ran out of memory."
                else -> "No trace was kept."
            }
            kind == CrashKind.NATIVE -> TombstoneDecoder.decode(trace)
            else -> trace.decodeToString().let { if (it.length > TRACE_LIMIT) it.take(TRACE_LIMIT) + "\n… (cut)" else it }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start-up failure"
        else -> "reason $reason"
    }

    private fun isMainProcess(context: Context): Boolean = processName(context) == context.packageName

    private fun processName(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().substringBefore('\u0000') }.getOrDefault(context.packageName)

    private const val MAX_EXITS = 16
    private const val FIRST_LOOK_BACK_MS = 3L * 24 * 60 * 60 * 1000
    private const val SHARE_TEXT_LIMIT = 100_000
    private const val TRACE_LIMIT = 400_000
}
