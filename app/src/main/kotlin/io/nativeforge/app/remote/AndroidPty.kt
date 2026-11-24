package io.nativeforge.app.remote

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.termux.terminal.PtyBridge
import io.nativeforge.core.remote.Pty
import io.nativeforge.core.remote.PtyCommand
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A process on a real pseudo-terminal, through the same native code as the app's terminal. */
class AndroidPty private constructor(private val fd: Int, private val pid: Int) : Pty {
    private val descriptor = ParcelFileDescriptor.fromFd(fd) // a duplicate: closing it does not close [fd]
    override val output: InputStream = FileInputStream(descriptor.fileDescriptor)
    override val input: OutputStream = FileOutputStream(descriptor.fileDescriptor)
    @Volatile private var closed = false

    override fun resize(rows: Int, columns: Int) {
        if (!closed) PtyBridge.setPtyWindowSize(fd, rows, columns)
    }

    override fun waitFor(): Int = PtyBridge.waitFor(pid)

    override fun close() {
        if (closed) return
        closed = true
        // Hang up the whole session like closing a terminal window does, then release the terminal.
        runCatching { Os.kill(-pid, OsConstants.SIGHUP) }
        runCatching { Os.kill(pid, OsConstants.SIGHUP) }
        runCatching { descriptor.close() }
        PtyBridge.close(fd)
    }

    companion object {
        private val SYSTEM_VARIABLES = setOf("BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT")
        private val DEFAULT_ENVIRONMENT = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8")

        fun start(command: PtyCommand, rows: Int, columns: Int): Pty {
            val pid = IntArray(1)
            java.io.File(command.workingDirectory).mkdirs()
            // Same environment rules as the app's terminal (ConsoleActivity): Android's own tools
            // need the class paths and ANDROID_* roots; everything else comes from the command.
            val system = System.getenv().filterKeys { it.startsWith("ANDROID_") || it in SYSTEM_VARIABLES }
            val env = (system + DEFAULT_ENVIRONMENT + command.environment).map { (k, v) -> "$k=$v" }.toTypedArray()
            // argv[0] is part of the argument array the native helper passes to execvp.
            val argv = (listOf(java.io.File(command.executable).name) + command.arguments).toTypedArray()
            val fd = PtyBridge.createSubprocess(
                command.executable,
                command.workingDirectory,
                argv,
                env,
                pid,
                rows,
                columns,
            )
            return AndroidPty(fd, pid[0])
        }
    }
}
