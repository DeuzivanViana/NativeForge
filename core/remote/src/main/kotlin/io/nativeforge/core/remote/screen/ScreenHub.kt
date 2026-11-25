package io.nativeforge.core.remote.screen

import io.nativeforge.core.common.Logger
import io.nativeforge.core.common.ThermalLevel
import io.nativeforge.core.project.model.ExportSettings
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
 * Wire format to the browser: text messages `hello`, `config`, `size`, `quality` and `end`
 * (JSON), and binary video packets `[flags:1][pts µs:8][Annex B data]` where flag 1 marks a
 * key frame (which then starts with the SPS and PPS). Browsers send pointer and wheel input
 * as small binary messages (see [Viewer.receive] for bytes), and keys, the size of their
 * panel (`resize`) and the rest as JSON.
 *
 * The display follows the browser's panel unless the program asked for a window of a
 * fixed size; then it keeps that size and the browser letterboxes it.
 *
 * The encoder's frame rate and bit rate follow the slowest viewer ([AdaptiveQuality]): its
 * queue length and the frames its decoder had to drop (`lag`). A hot phone ([thermalChanged])
 * caps them, and with nobody watching for a few seconds the encoder idles at
 * [StreamQuality.IDLE] until a browser joins again.
 */
class ScreenHub(
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxQueuedBytes: Long = 2L * 1024 * 1024,
) {
    /** Tells the main channel that a stream started (`screen.start`) or ended (`screen.end`). */
    @Volatile var onEvent: (JsonObject) -> Unit = {}

    @Volatile private var current: Session? = null

    @Volatile private var thermal = ThermalLevel.NONE

    /** The phone's temperature changed: hot phones stream at a lower frame rate. */
    fun thermalChanged(level: ThermalLevel) {
        thermal = level
        current?.thermalChanged(level)
    }

    val session: Session? get() = current

    inner class Session internal constructor(
        val id: String,
        val title: String,
        private val orientation: ExportSettings.Orientation,
    ) {
        @Volatile internal var stream: ProgramStream? = null
        @Volatile internal var config: ByteArray? = null
        @Volatile internal var codec: String? = null
        @Volatile internal var ended = false
        internal val viewers = CopyOnWriteArrayList<Viewer>()
        private val lastKeyRequest = AtomicLong(0)

        /** The program asked for a window that does not resize; browser panels don't change it. */
        @Volatile var fixedSize = false
            private set

        /** Display size for the latest browser panel. */
        @Volatile private var panel: Pair<Int, Int>? = null

        /** A window request that came before [stream] was set. */
        @Volatile private var pendingWindow: Triple<Int, Int, Boolean>? = null

        val viewerCount: Int get() = viewers.size

        private val quality = AdaptiveQuality(clock).also { it.thermalChanged(thermal) }
        private val qualityLock = Any()

        /** What the encoder was last told; streams start at the best level. */
        @Volatile var appliedQuality: StreamQuality = StreamQuality.LEVELS[0]
            private set
        private var idle = false
        private var noViewersSince = clock()
        private var lastSample = 0L

        internal val sink = object : StreamSink {
            override fun onFrame(frame: VideoFrame) = deliver(this@Session, frame)
            override fun onEnded(reason: String?) = end(this@Session, reason)
            override fun onWindowRequest(width: Int, height: Int, resizable: Boolean) = windowRequest(width, height, resizable)
        }

        @Synchronized
        internal fun windowRequest(width: Int, height: Int, resizable: Boolean) {
            if (ended) return
            if (stream == null) {
                pendingWindow = Triple(width, height, resizable)
                return
            }
            fixedSize = !resizable
            when {
                resizable -> panel?.let { (w, h) -> resize(w, h) }
                width > 0 && height > 0 -> StreamSize.program(width, height).let { (w, h) -> resize(w, h) }
            }
            sendSize()
        }

        /** A browser panel of [width] x [height] pixels (the last one to change wins). */
        @Synchronized
        internal fun panelResized(width: Int, height: Int) {
            val size = StreamSize.fit(width.coerceIn(1, MAX_PANEL), height.coerceIn(1, MAX_PANEL), orientation)
            panel = size
            if (fixedSize || stream == null) return
            if (resize(size.first, size.second)) sendSize()
        }

        internal fun started() {
            pendingWindow?.let { (w, h, resizable) -> pendingWindow = null; windowRequest(w, h, resizable) }
            synchronized(qualityLock) { applyQuality() }
        }

        /** A viewer fell behind: lower the quality (at most every few seconds). */
        internal fun congested() = synchronized(qualityLock) { if (quality.congested()) applyQuality() }

        internal fun thermalChanged(level: ThermalLevel) = synchronized(qualityLock) { if (quality.thermalChanged(level)) applyQuality() }

        /** A viewer joined: leave the idle quality. */
        internal fun viewerJoined() = synchronized(qualityLock) {
            idle = false
            applyQuality()
        }

        /** Called for each encoded frame: measures the viewers' queues, idles without viewers. */
        internal fun onFrameEncoded() {
            val now = clock()
            synchronized(qualityLock) {
                if (viewers.isEmpty()) {
                    if (noViewersSince == 0L) noViewersSince = now
                    if (!idle && now - noViewersSince >= IDLE_AFTER_MS) {
                        idle = true
                        applyQuality()
                    }
                    return
                }
                noViewersSince = 0L
                if (now - lastSample < SAMPLE_INTERVAL_MS) return
                lastSample = now
                val stream = stream ?: return
                val bitRate = appliedQuality.bitRate(stream.width, stream.height).coerceAtLeast(1)
                val worst = viewers.maxOfOrNull { it.queuedVideoBytes } ?: 0L
                if (quality.sample(worst * 8_000 / bitRate)) applyQuality()
            }
        }

        /** Caller holds [qualityLock]. */
        private fun applyQuality() {
            if (ended) return
            val target = if (idle) StreamQuality.IDLE else quality.current
            if (target == appliedQuality) return
            appliedQuality = target
            logger.info(TAG, "Stream quality: ${target.frameRate} fps, ${(target.bitRateScale * 100).toInt()}% bit rate" + if (idle) " (nobody watching)" else if (quality.limitedByHeat) " (phone is hot)" else "")
            runCatching { stream?.setQuality(target) }.onFailure { logger.warn(TAG, "Could not change the stream quality", it) }
            if (!idle) {
                val text = qualityJson().toString()
                viewers.forEach { it.sendText(text) }
            }
        }

        internal fun qualityJson() = Json.obj("t" to "quality", "fps" to appliedQuality.frameRate, "hot" to quality.limitedByHeat)

        private fun resize(width: Int, height: Int): Boolean {
            val stream = stream ?: return false
            if (stream.width == width && stream.height == height) return false
            runCatching { stream.resize(width, height) }.onFailure { logger.warn(TAG, "Could not resize the stream", it) }
            return true
        }

        private fun sendSize() {
            val stream = stream ?: return
            val text = Json.obj("t" to "size", "width" to stream.width, "height" to stream.height, "fixed" to fixedSize).toString()
            viewers.forEach { it.sendText(text) }
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
        private val queuedVideo = AtomicLong()

        /** Video bytes waiting to go out to this browser. */
        internal val queuedVideoBytes: Long get() = queuedVideo.get()
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
                queue.removeIf { it is ByteArray }.also {
                    queuedBytes.set(queue.sumOf { (it as? String)?.length?.toLong() ?: 0L })
                    queuedVideo.set(0)
                }
                needsKeyFrame = true
                session.congested()
                return false
            }
            queuedVideo.addAndGet(packet.size.toLong())
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
                        is ByteArray -> {
                            queuedBytes.addAndGet(-item.size.toLong())
                            socket.sendBinary(item)
                            queuedVideo.updateAndGet { (it - item.size).coerceAtLeast(0) }
                        }
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
                // The browser's decoder could not keep up and dropped frames.
                "lag" -> session.congested()
                "release" -> releaseKeys()
                "resize" -> {
                    val w = message.int("w") ?: return
                    val h = message.int("h") ?: return
                    session.panelResized(w, h)
                }
            }
        }

        /**
         * Handles one binary input message (big endian), sent for the frequent events:
         * * pointer `[1][action:1][flags:1][id:1][buttons:1][x:f32][y:f32]`, where action is
         *   0 down, 1 move, 2 up, 3 cancel, 4 hover and flag 1 marks a touch;
         * * wheel `[2][x:f32][y:f32][dx:f32][dy:f32]`.
         *
         * Positions are fractions of the picture, as in the JSON messages.
         */
        fun receive(data: ByteArray) {
            val stream = session.stream ?: return
            if (data.isEmpty()) return
            val buffer = ByteBuffer.wrap(data)
            when (data[0].toInt()) {
                BIN_POINTER -> {
                    if (data.size < 13) return
                    val action = POINTER_ACTIONS.getOrNull(data[1].toInt()) ?: return
                    val mouse = data[2].toInt() and 1 == 0
                    val id = data[3].toInt() and 0xff
                    val buttons = data[4].toInt() and 0xff
                    pointer(action, finite(buffer.getFloat(5)), finite(buffer.getFloat(9)), id, mouse, buttons, stream)?.let(stream::input)
                }
                BIN_WHEEL -> {
                    if (data.size < 17) return
                    wheel(
                        finite(buffer.getFloat(1)), finite(buffer.getFloat(5)),
                        finite(buffer.getFloat(9)) ?: 0.0, finite(buffer.getFloat(13)) ?: 0.0, stream,
                    )?.let(stream::input)
                }
            }
        }

        private fun finite(value: Float): Double? = value.toDouble().takeIf { it.isFinite() }

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
    fun start(
        title: String,
        orientation: ExportSettings.Orientation = ExportSettings.Orientation.UNSPECIFIED,
        open: (StreamSink) -> ProgramStream,
    ): Session {
        stop("Replaced by a new run")
        val session = Session(newId(), title, orientation)
        current = session
        try {
            session.stream = open(session.sink)
        } catch (e: Exception) {
            current = null
            throw e
        }
        if (session.ended) throw IllegalStateException("The program ended right away")
        session.started()
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
                "fixed" to session.fixedSize,
            ).toString(),
        )
        session.viewers += viewer
        viewer.sendText(session.qualityJson().toString())
        session.viewerJoined()
        session.requestKeyFrame()
        return viewer
    }

    private fun deliver(session: Session, frame: VideoFrame) {
        if (session.ended || current !== session) return
        if (frame.config) {
            session.config = frame.data
            session.codec = H264.codecString(frame.data) ?: session.codec
            val stream = session.stream
            val text = Json.obj(
                "t" to "config", "codec" to session.codec, "width" to stream?.width, "height" to stream?.height, "fixed" to session.fixedSize,
            ).toString()
            session.viewers.forEach { it.sendText(text) }
            return
        }
        session.onFrameEncoded()
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
        return pointer(action, message.number("x"), message.number("y"), message.int("id") ?: 0, message.string("k") != "touch", message.int("b") ?: 0, stream)
    }

    private fun pointer(action: PointerAction, x: Double?, y: Double?, id: Int, mouse: Boolean, buttons: Int, stream: ProgramStream): RemoteInput.Pointer? {
        val fx = x?.coerceIn(0.0, 1.0)?.toFloat() ?: return null
        val fy = y?.coerceIn(0.0, 1.0)?.toFloat() ?: return null
        // Browser button bits (1 left, 2 right, 4 middle) match Android's primary, secondary, tertiary.
        return RemoteInput.Pointer(action, fx * stream.width, fy * stream.height, id.coerceIn(0, MAX_POINTER_ID), mouse, buttons and 0x7)
    }

    private fun parseWheel(message: JsonObject, stream: ProgramStream): RemoteInput.Scroll? =
        wheel(message.number("x"), message.number("y"), message.number("dx") ?: 0.0, message.number("dy") ?: 0.0, stream)

    private fun wheel(x: Double?, y: Double?, dx: Double, dy: Double, stream: ProgramStream): RemoteInput.Scroll? {
        val fx = x?.coerceIn(0.0, 1.0)?.toFloat() ?: return null
        val fy = y?.coerceIn(0.0, 1.0)?.toFloat() ?: return null
        val sx = dx.coerceIn(-MAX_SCROLL, MAX_SCROLL)
        val sy = dy.coerceIn(-MAX_SCROLL, MAX_SCROLL)
        // Browsers count down as positive; Android's vertical scroll axis counts up.
        return RemoteInput.Scroll(fx * stream.width, fy * stream.height, sx.toFloat(), (-sy).toFloat())
    }

    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.merge(other: JsonObject) = JsonObject(toMap() + other.toMap())

    private fun newId(): String = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "ScreenHub"
        private const val KEY_REQUEST_INTERVAL_MS = 300L
        private const val MAX_POINTER_ID = 9
        private const val MAX_SCROLL = 50.0
        private const val MAX_PANEL = 8192
        private const val SAMPLE_INTERVAL_MS = 500L
        private const val IDLE_AFTER_MS = 5_000L
        private const val BIN_POINTER = 1
        private const val BIN_WHEEL = 2
        private val POINTER_ACTIONS = listOf(PointerAction.DOWN, PointerAction.MOVE, PointerAction.UP, PointerAction.CANCEL, PointerAction.HOVER)
        private val EMPTY = ByteArray(0)
        private val STOP = Any()
        private val random = SecureRandom()
    }
}
