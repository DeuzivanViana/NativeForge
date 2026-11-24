package io.nativeforge.core.remote

import io.nativeforge.core.common.Logger
import io.nativeforge.core.remote.http.WebSocket
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Terminal sessions shared with browsers. A session outlives its WebSocket: closing the tab
 * or losing Wi-Fi leaves the shell running, and reconnecting replays the recent output
 * ([SCROLLBACK_BYTES]), like tmux. Sessions end when their process exits and the browser
 * closes them, or when the server stops.
 */
class TerminalHub(private val factory: PtyFactory, private val logger: Logger) {

    inner class Session(val id: String, val title: String, private val pty: Pty) {
        private val scrollback = ByteRing(SCROLLBACK_BYTES)
        val clients = CopyOnWriteArraySet<WebSocket>()
        @Volatile var exitCode: Int? = null
            private set
        @Volatile var lastClientAt: Long = System.currentTimeMillis()

        internal fun start() {
            Thread({
                val buffer = ByteArray(16 * 1024)
                try {
                    while (true) {
                        val n = pty.output.read(buffer)
                        if (n < 0) break
                        synchronized(this) {
                            scrollback.write(buffer, 0, n)
                            clients.forEach { client -> runCatching { client.sendBinary(buffer, 0, n) }.onFailure { clients.remove(client) } }
                        }
                    }
                } catch (e: IOException) {
                    // EIO when the process side of the terminal closes: normal end.
                }
                val code = runCatching { pty.waitFor() }.getOrDefault(-1)
                exitCode = code
                val message = Json.obj("t" to "exit", "code" to code).toString()
                clients.forEach { runCatching { it.sendText(message) } }
            }, "nf-pty-$id").apply { isDaemon = true }.start()
        }

        fun attach(socket: WebSocket) {
            synchronized(this) {
                val replay = scrollback.toByteArray()
                if (replay.isNotEmpty()) socket.sendBinary(replay)
                clients += socket
            }
            exitCode?.let { socket.sendText(Json.obj("t" to "exit", "code" to it).toString()) }
        }

        fun detach(socket: WebSocket) {
            clients -= socket
            lastClientAt = System.currentTimeMillis()
        }

        fun write(bytes: ByteArray) {
            if (exitCode != null) return
            runCatching {
                pty.input.write(bytes)
                pty.input.flush()
            }
        }

        fun resize(rows: Int, columns: Int) {
            if (exitCode == null) runCatching { pty.resize(rows.coerceIn(2, 500), columns.coerceIn(2, 1000)) }
        }

        internal fun close() {
            clients.forEach { runCatching { it.close(1000, "Terminal closed") } }
            clients.clear()
            runCatching { pty.close() }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val ids = AtomicInteger()

    fun open(command: PtyCommand, title: String, rows: Int, columns: Int): Session {
        pruneFinished()
        if (sessions.size >= MAX_SESSIONS) {
            // Drop the oldest finished session first, then refuse.
            sessions.values.filter { it.exitCode != null }.minByOrNull { it.lastClientAt }?.let { close(it.id) }
            if (sessions.size >= MAX_SESSIONS) throw IllegalStateException("Too many terminals are open (at most $MAX_SESSIONS)")
        }
        val pty = factory.start(command, rows.coerceIn(2, 500), columns.coerceIn(2, 1000))
        val id = "t${ids.incrementAndGet()}"
        val session = Session(id, title, pty)
        sessions[id] = session
        session.start()
        logger.info(TAG, "Terminal $id started: ${command.executable}")
        return session
    }

    operator fun get(id: String?): Session? = id?.let(sessions::get)

    fun list(): List<Session> = sessions.values.sortedBy { it.id.drop(1).toIntOrNull() ?: 0 }

    fun close(id: String) {
        sessions.remove(id)?.close()
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    /** True while any terminal still runs a process. */
    val hasRunningProcess: Boolean get() = sessions.values.any { it.exitCode == null }

    private fun pruneFinished() {
        val cutoff = System.currentTimeMillis() - FINISHED_RETENTION_MILLIS
        sessions.values.filter { it.exitCode != null && it.clients.isEmpty() && it.lastClientAt < cutoff }.forEach { close(it.id) }
    }

    companion object {
        private const val TAG = "TerminalHub"
        const val MAX_SESSIONS = 8
        const val SCROLLBACK_BYTES = 256 * 1024
        private const val FINISHED_RETENTION_MILLIS = 10 * 60 * 1000L
    }
}

/** Fixed-size ring of the most recent bytes written. */
class ByteRing(private val capacity: Int) {
    private val data = ByteArray(capacity)
    private var start = 0
    private var size = 0

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var off = offset
        var len = length
        if (len >= capacity) {
            off += len - capacity
            len = capacity
        }
        for (i in 0 until len) {
            val pos = (start + size) % capacity
            data[pos] = bytes[off + i]
            if (size < capacity) size++ else start = (start + 1) % capacity
        }
    }

    fun toByteArray(): ByteArray = ByteArray(size) { data[(start + it) % capacity] }
}
