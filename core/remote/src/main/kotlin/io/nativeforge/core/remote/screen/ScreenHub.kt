package io.nativeforge.core.remote.screen

import io.nativeforge.core.common.Logger
import io.nativeforge.core.remote.Json
import io.nativeforge.core.remote.bool
import io.nativeforge.core.remote.int
import io.nativeforge.core.remote.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** A browser connection that shows the stream (a WebSocket in the server, a fake in tests). */
interface ScreenViewer {
    fun sendText(text: String)
    fun sendBinary(data: ByteArray)
    fun close()
}

/**
 * Runs at most one streamed program at a time and fans its video out to the browsers
 * watching it. Each viewer has its own queue and sender thread, so a slow browser never
 * stalls the encoder: when its queue grows too long it is emptied and the viewer waits
 * for the next key frame.
 *
 * Wire format to the browser: text messages `hello`, `config` and `end` (JSON), and
 * binary video packets `[flags:1][pts µs:8][Annex B data]` where flag 1 marks a key
 * frame (which then starts with the SPS and PPS). Browsers send input as JSON.
 */
class ScreenHub(
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxQueuedBytes: Long = 2L * 1024 * 1024,
) {
    /** Tells the main channel that a stream started (`screen.start`) or ended (`screen.end`). */
    @Volatile var onEvent: (JsonObject) -> Unit = {}

    @Volatile private var current: Session? = null

    val session: Session? get() = current

    inner class Session internal constructor(val id: String, val title: String) {
        @Volatile internal var stream: ProgramStream? = null
        @Volatile internal var config: ByteArray? = null
        @Volatile internal var codec: String? = null
        @Volatile internal var ended = false
        internal val viewers = CopyOnWriteArrayList<Viewer>()
        private val lastKeyRequest = AtomicLong(0)

        val viewerCount: Int get() = viewers.size

        internal val sink = object : StreamSink {
            override fun onFrame(frame: VideoFrame) = deliver(this@Session, frame)
            override fun onEnded(reason: String?) = end(this@Session, reason)
        }

        internal fun requestKeyFrame() {
            val now = clock()
            val last = lastKeyRequest.get()
            if (now - last < KEY_REQUEST_INTERVAL_MS && last != 0L) return
            if (lastKeyRequest.compareAndSet(last, now)) runCatching { stream?.requestKeyFrame() }
        }

        internal fun json() = Json.obj("id" to id, "title" to title)
    }

    /** One browser watching a session. */
    inner class Viewer internal constructor(private val socket: ScreenViewer, internal val session: Session) {
        private val queue = LinkedBlockingQueue<Any>()
        private val queuedBytes = AtomicLong()
        @Volatile internal var needsKeyFrame = true
        @Volatile private var closed = false

        /** Android key codes of keys held down, by browser `code`, so each key-up matches its key-down. */
        internal val pressed = HashMap<String, Int>()

        private val thread = Thread(::pump, "nf-screen-viewer").apply {
            isDaemon = true
            start()
        }

        internal fun sendText(text: String) = offer(text, text.length.toLong())

        /** False when the viewer is too far behind; it then waits for a key frame. */
        internal fun sendVideo(packet: ByteArray): Boolean {
            if (queuedBytes.get() + packet.size > maxQueuedBytes) {
                queue.removeIf { it is ByteArray }.also { queuedBytes.set(queue.sumOf { (it as? String)?.length?.toLong() ?: 0L }) }
                needsKeyFrame = true
                return false
            }
            offer(packet, packet.size.toLong())
            return true
        }

        private fun offer(item: Any, size: Long) {
            if (closed) return
            queuedBytes.addAndGet(size)
            queue.put(item)
        }

        private fun pump() {
            try {
                while (true) {
                    val item = queue.take()
                    if (item === STOP) break
                    when (item) {
                        is String -> { queuedBytes.addAndGet(-item.length.toLong()); socket.sendText(item) }
                        is ByteArray -> { queuedBytes.addAndGet(-item.size.toLong()); socket.sendBinary(item) }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                logger.debug(TAG, "Screen viewer went away: ${e.message}")
            }
            close()
            runCatching { socket.close() }
        }

        /** Stops sending; queued messages already taken are dropped. */
        fun close() {
            if (closed) return
            closed = true
            session.viewers.remove(this)
            releaseKeys()
            queue.clear()
            queue.offer(STOP)
        }

        /** Lets go of every key this viewer holds (the page lost focus or closed). */
        internal fun releaseKeys() {
            val keys = synchronized(pressed) { pressed.values.toList().also { pressed.clear() } }
            keys.forEach { code -> runCatching { session.stream?.input(RemoteInput.Key(false, code, 0)) } }
        }

        /** Sends `end` after what is queued, then closes. */
        internal fun finish(reason: String?) {
            sendText(Json.obj("t" to "end", "reason" to reason).toString())
            closed = true
            session.viewers.remove(this)
            queue.offer(STOP)
        }

        /** Handles one input message from the browser. */
        fun receive(text: String) {
            val message = Json.parseObject(text) ?: return
            val stream = session.stream ?: return
            when (message.string("t")) {
                "key" -> parseKey(message)?.let(stream::input)
                "ptr" -> parsePointer(message, stream)?.let(stream::input)
                "wheel" -> parseWheel(message, stream)?.let(stream::input)
                "keyframe" -> session.requestKeyFrame()
                "release" -> releaseKeys()
            }
        }

        private fun parseKey(message: JsonObject): RemoteInput.Key? {
            val down = message.bool("d") ?: return null
            val code = message.string("code")
            val meta = KeyMap.metaState(message.int("m") ?: 0)
            val keyCode = synchronized(pressed) {
                if (down) {
                    val mapped = code?.let(pressed::get) ?: KeyMap.keyCode(code, message.string("key")) ?: return null
                    code?.let { pressed[it] = mapped }
                    mapped
                } else {
                    code?.let(pressed::remove) ?: KeyMap.keyCode(code, message.string("key")) ?: return null
                }
            }
            return RemoteInput.Key(down, keyCode, meta, if (message.bool("r") == true) 1 else 0)
        }
    }

    /**
     * Starts a stream, stopping the previous one. [open] creates the platform stream and
     * must deliver its output to the given sink.
     */
    fun start(title: String, open: (StreamSink) -> ProgramStream): Session {
        stop("Replaced by a new run")
        val session = Session(newId(), title)
        current = session
        try {
            session.stream = open(session.sink)
        } catch (e: Exception) {
            current = null
            throw e
        }
        if (session.ended) throw IllegalStateException("The program ended right away")
        onEvent(Json.obj("t" to "screen.start").merge(session.json()))
        return session
    }

    /** Stops the current stream, if any. */
    fun stop(reason: String = "Stopped") {
        val session = current ?: return
        runCatching { session.stream?.stop() }.onFailure { logger.warn(TAG, "Could not stop the stream", it) }
        end(session, reason)
    }

    /** Connects a browser to session [id]; null when that session is not running. */
    fun attach(socket: ScreenViewer, id: String?): Viewer? {
        val session = current?.takeIf { it.id == id && !it.ended } ?: return null
        val stream = session.stream ?: return null
        val viewer = Viewer(socket, session)
        viewer.sendText(
            Json.obj(
                "t" to "hello",
                "id" to session.id,
                "title" to session.title,
                "width" to stream.width,
                "height" to stream.height,
                "codec" to session.codec,
            ).toString(),
        )
        session.viewers += viewer
        session.requestKeyFrame()
        return viewer
    }

    private fun deliver(session: Session, frame: VideoFrame) {
        if (session.ended || current !== session) return
        if (frame.config) {
            session.config = frame.data
            session.codec = H264.codecString(frame.data) ?: session.codec
            val stream = session.stream
            val text = Json.obj("t" to "config", "codec" to session.codec, "width" to stream?.width, "height" to stream?.height).toString()
            session.viewers.forEach { it.sendText(text) }
            return
        }
        if (session.viewers.isEmpty()) return
        val packet by lazy(LazyThreadSafetyMode.NONE) { packet(session, frame) }
        var behind = false
        session.viewers.forEach { viewer ->
            if (viewer.needsKeyFrame) {
                if (!frame.keyFrame || session.codec == null) {
                    behind = true
                    return@forEach
                }
                viewer.needsKeyFrame = false
            }
            if (!viewer.sendVideo(packet)) behind = true
        }
        if (behind) session.requestKeyFrame()
    }

    private fun packet(session: Session, frame: VideoFrame): ByteArray {
        val prefix = if (frame.keyFrame) session.config ?: EMPTY else EMPTY
        return ByteBuffer.allocate(9 + prefix.size + frame.data.size)
            .put(if (frame.keyFrame) 1 else 0)
            .putLong(frame.ptsMicros)
            .put(prefix)
            .put(frame.data)
            .array()
    }

    private fun end(session: Session, reason: String?) {
        if (session.ended) return
        session.ended = true
        if (current === session) current = null
        session.viewers.toList().forEach { it.finish(reason) }
        onEvent(Json.obj("t" to "screen.end", "reason" to reason).merge(session.json()))
    }

    private fun parsePointer(message: JsonObject, stream: ProgramStream): RemoteInput.Pointer? {
        val action = when (message.string("a")) {
            "down" -> PointerAction.DOWN
            "move" -> PointerAction.MOVE
            "up" -> PointerAction.UP
            "cancel" -> PointerAction.CANCEL
            "hover" -> PointerAction.HOVER
            else -> return null
        }
        val x = message.fraction("x") ?: return null
        val y = message.fraction("y") ?: return null
        val mouse = message.string("k") != "touch"
        // Browser button bits (1 left, 2 right, 4 middle) match Android's primary, secondary, tertiary.
        val buttons = (message.int("b") ?: 0) and 0x7
        val id = (message.int("id") ?: 0).coerceIn(0, MAX_POINTER_ID)
        return RemoteInput.Pointer(action, x * stream.width, y * stream.height, id, mouse, buttons)
    }

    private fun parseWheel(message: JsonObject, stream: ProgramStream): RemoteInput.Scroll? {
        val x = message.fraction("x") ?: return null
        val y = message.fraction("y") ?: return null
        val dx = message.number("dx")?.coerceIn(-MAX_SCROLL, MAX_SCROLL) ?: 0.0
        val dy = message.number("dy")?.coerceIn(-MAX_SCROLL, MAX_SCROLL) ?: 0.0
        // Browsers count down as positive; Android's vertical scroll axis counts up.
        return RemoteInput.Scroll(x * stream.width, y * stream.height, dx.toFloat(), (-dy).toFloat())
    }

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.fraction(key: String) = number(key)?.coerceIn(0.0, 1.0)?.toFloat()

    private fun JsonObject.merge(other: JsonObject) = JsonObject(toMap() + other.toMap())

    private fun newId(): String = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ScreenHub"
        private const val KEY_REQUEST_INTERVAL_MS = 300L
        private const val MAX_POINTER_ID = 9
        private const val MAX_SCROLL = 50.0
        private val EMPTY = ByteArray(0)
        private val STOP = Any()
        private val random = SecureRandom()
    }
}
