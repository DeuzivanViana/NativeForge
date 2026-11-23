package io.nativeforge.feature.runner

import android.system.Os
import java.io.File

/**
 * Hands start-up errors from the runner process back to the IDE. The IDE sets
 * [ENV] to a file path in the launch environment; the runner (and the native
 * shim) write the reason there when a program cannot start.
 */
object RunErrors {
    const val ENV = "NATIVEFORGE_ERROR_FILE"

    fun write(message: String) {
        val path = runCatching { Os.getenv(ENV) }.getOrNull() ?: return
        runCatching { File(path).apply { parentFile?.mkdirs() }.writeText(message + "\n") }
    }
}
