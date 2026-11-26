package io.nativeforge.app.testing

/**
 * Robolectric runs the app's `onCreate`, which installs the crash reporter. With no handler
 * before it (only on the JVM: Android always has one) the reporter ends the process with
 * exit code 10, so one exception in a screen's background work, even after its test ended,
 * killed the whole test JVM. Tests call [printInsteadOfExit] first: such an error is printed
 * to the test's output, and later crash reporters chain to this handler instead of exiting.
 */
object UncaughtErrors {
    fun printInsteadOfExit() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            System.err.println("Uncaught exception in thread \"${thread.name}\":")
            error.printStackTrace()
        }
    }
}
