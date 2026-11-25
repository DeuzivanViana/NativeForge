package io.nativeforge.core.common.process

import java.io.InputStream
import java.io.OutputStream

/**
 * Daemon threads that can never take the app down.
 *
 * On Android an exception that escapes any thread ends the whole process, and stream threads
 * meet exceptions in normal use: `Process.destroy()` closes the process's pipes, so a thread
 * still reading its stderr gets "Stream closed", and a thread writing to a program that exited
 * early gets "Broken pipe". Threads started here end quietly instead.
 */
object BackgroundThreads {

    /**
     * Runs [block] on a new daemon thread. An [Exception] it throws ends the thread and goes to
     * [onError] (which must not throw); an [Error] such as running out of memory still escapes.
     */
    fun start(name: String, onError: (Exception) -> Unit = {}, block: () -> Unit): Thread =
        Thread({
            try {
                block()
            } catch (e: Exception) {
                runCatching { onError(e) }
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    /** Reads [stream] line by line until it ends, fails or is closed by someone else. */
    fun drainLines(stream: InputStream, name: String, onLine: (String) -> Unit = {}): Thread =
        start(name) { stream.bufferedReader().useLines { lines -> lines.forEach(onLine) } }

    /** Writes [bytes] to [stream] and closes it; a reader that went away is not an error. */
    fun writeAndClose(stream: OutputStream, bytes: ByteArray, name: String): Thread =
        start(name) { stream.use { it.write(bytes) } }
}
