package io.nativeforge.feature.runner.crash

import java.io.File

/**
 * Records native crashes (SIGSEGV, SIGABRT...) of the process that installs it: the signal
 * and a backtrace go to a file, then the signal goes on to Android's own handler, so the app
 * still ends and the system tombstone is still written. See `src/main/cpp/nf_crash_handler.c`.
 *
 * Lives in this module because its native build is here; the IDE process installs it
 * (`io.nativeforge.app.crash.CrashReporter`). Program hosts in the `:runner` process do not:
 * a crash of the user's program is not a crash of the IDE.
 */
object NativeCrashHandler {

    /** Installs the handler once; false when the library could not be loaded. */
    fun install(reportFile: File): Boolean = runCatching {
        System.loadLibrary("nativeforge_crash")
        reportFile.parentFile?.mkdirs()
        nativeInstall(reportFile.absolutePath)
    }.getOrDefault(false)

    @JvmStatic
    private external fun nativeInstall(path: String): Boolean
}
