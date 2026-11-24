package io.nativeforge.app.remote

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Surface
import io.nativeforge.core.common.Logger
import io.nativeforge.core.remote.screen.PointerAction
import io.nativeforge.core.remote.screen.ProgramStream
import io.nativeforge.core.remote.screen.RemoteInput
import io.nativeforge.core.remote.screen.StreamSink
import io.nativeforge.core.remote.screen.VideoFrame
import io.nativeforge.feature.runner.ProgramLauncher
import io.nativeforge.feature.runner.RemoteInputWire
import io.nativeforge.feature.runner.RemoteRun
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The phone side of a streamed run: a private virtual display whose picture goes straight
 * into the hardware H.264 encoder (no copies through the CPU), and the local socket the
 * program's process reads browser input from and sends its window size requests to.
 * The display changes size with [resize] (see ScreenHub for when).
 *
 * The program itself is started by the caller with [run]; it draws on [display] through a
 * Presentation (see the runner's RemoteHost).
 */
class AndroidProgramStream private constructor(
    private val context: Context,
    private val logger: Logger,
    width: Int,
    height: Int,
    private val sink: StreamSink,
    /** Reason shown when the program's process goes away (its start error, if it wrote one). */
    private val endReason: () -> String?,
    /** Keep the phone on behind a black screen while the program runs (see RemoteScreenActivity). */
    private val blackScreen: Boolean,
    /** What sizes the encoder takes (null when Android did not say). */
    private val capabilities: MediaCodecInfo.VideoCapabilities?,
) : ProgramStream {
    private val socketName = "nativeforge.remote-input." + ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val server = LocalServerSocket(socketName)
    private val display: VirtualDisplay
    private val stopped = AtomicBoolean()
    private val ended = AtomicBoolean()
    private val resizeLock = Any()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "nf-stream-input").apply { isDaemon = true } }
    @Volatile private var encoder: Encoder
    @Volatile private var client: LocalSocket? = null
    @Volatile private var output: DataOutputStream? = null

    override val width: Int get() = encoder.width
    override val height: Int get() = encoder.height

    val run: RemoteRun

    init {
        try {
            encoder = Encoder(width, height)
        } catch (e: Exception) {
            runCatching { server.close() }
            throw e
        }
        try {
            display = context.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("NativeForge remote", width, height, dpi(width, height), encoder.surface, 0)
                ?: throw IllegalStateException("Android did not create the off-screen display")
        } catch (e: Exception) {
            encoder.stop()
            encoder.start() // its thread releases it
            runCatching { server.close() }
            throw e
        }
        run = RemoteRun(display.display.displayId, socketName, blackScreen)
        encoder.start()
        Thread(::serve, "nf-stream-socket").apply { isDaemon = true }.start()
    }

    /** Same physical size of things as on the phone, scaled to the display's size. */
    private fun dpi(width: Int, height: Int): Int {
        val metrics = context.resources.displayMetrics
        val phoneShortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        return (metrics.densityDpi * minOf(width, height) / phoneShortSide.toFloat()).roundToInt().coerceIn(120, 640)
    }

    /**
     * One hardware encoder whose input surface the display draws into. A resize starts a
     * new one (an encoder's size is fixed) and stops the old one; only the current one's
     * output reaches the sink.
     */
    private inner class Encoder(val width: Int, val height: Int) {
        private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
        val surface: Surface
        @Volatile private var stopping = false

        init {
            try {
                codec.configure(format(width, height), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = codec.createInputSurface()
                codec.start()
            } catch (e: Exception) {
                runCatching { codec.release() }
                throw e
            }
        }

        private val current get() = encoder === this && !stopping

        fun start() = Thread(::drain, "nf-stream-encoder").apply { isDaemon = true }.start()

        fun stop() {
            stopping = true
        }

        fun requestKeyFrame() {
            runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        /** Copies encoded frames to the sink until stopped, then releases the encoder. */
        private fun drain() {
            val info = MediaCodec.BufferInfo()
            try {
                while (!stopping && !stopped.get()) {
                    val index = codec.dequeueOutputBuffer(info, 100_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            configFromFormat(codec.outputFormat)?.takeIf { current }?.let(sink::onFrame)
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && current) {
                                val bytes = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.get(bytes)
                                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                sink.onFrame(VideoFrame(bytes, info.presentationTimeUs, key && !config, config))
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            } catch (e: Exception) {
                if (current && !stopped.get()) {
                    logger.error(TAG, "Video encoder failed", e)
                    finish("The video encoder failed: ${e.message}")
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                runCatching { surface.release() }
            }
        }
    }

    private fun format(width: Int, height: Int) = MediaFormat.createVideoFormat(MIME, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate(width, height))
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        // Keep sending frames while the picture stands still, so new viewers get one soon.
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }

    /**
     * Gives the display a new size: a new encoder of that size takes over, then the old one
     * stops. The program's window on the display follows (its surface changes size).
     */
    override fun resize(width: Int, height: Int) {
        synchronized(resizeLock) {
            if (stopped.get()) return
            val (w, h) = encoderSize(capabilities, width, height)
            val old = encoder
            if (w == old.width && h == old.height) return
            val next = try {
                Encoder(w, h)
            } catch (e: Exception) {
                logger.warn(TAG, "Could not start an encoder for $w x $h", e)
                return
            }
            encoder = next
            display.resize(w, h, dpi(w, h))
            display.surface = next.surface
            next.start()
            old.stop()
            logger.info(TAG, "Display resized to $w x $h")
        }
    }

    /**
     * Waits for the program's process, then reads its window requests until the connection
     * closes, which means the program ended.
     */
    private fun serve() {
        try {
            val socket = acceptOwnProcess() ?: return
            client = socket
            output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            val input = DataInputStream(socket.inputStream)
            input.readInt() // the display the program is on; its arrival means the program is up
            logger.info(TAG, "Streamed program connected")
            while (true) {
                when (val type = input.read()) {
                    -1 -> break
                    RemoteInputWire.WINDOW -> {
                        val w = input.readInt()
                        val h = input.readInt()
                        val resizable = input.readBoolean()
                        logger.info(TAG, "The program asked for a ${w}x$h window${if (resizable) " (resizable)" else ""}")
                        sink.onWindowRequest(w, h, resizable)
                        // The program may wait for this before it sets up its framebuffer (raylib).
                        send { out -> RemoteInputWire.writeSize(out, width, height) }
                    }
                    else -> throw IOException("Unknown message $type from the program")
                }
            }
        } catch (e: IOException) {
            logger.debug(TAG, "Program connection ended: ${e.message}")
        }
        if (!stopped.get()) finish(endReason() ?: "The program ended")
    }

    private fun acceptOwnProcess(): LocalSocket? {
        // Nobody connects when Android refused to start the activity (screen locked, app in the background).
        val watchdog = Thread {
            try {
                Thread.sleep(START_TIMEOUT_MS)
                if (client == null && !stopped.get()) {
                    finish(
                        endReason() ?: "The program did not start: Android only opens it while the phone is unlocked. " +
                            "Unlock it (or turn on Black screen in Remote access) and run again.",
                    )
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            while (!stopped.get()) {
                val socket = server.accept()
                if (stopped.get()) { socket.close(); return null }
                // Abstract sockets are open to every app: talk only to this app's own processes.
                if (socket.peerCredentials.uid == Process.myUid()) return socket
                logger.warn(TAG, "Refused an input connection from uid ${socket.peerCredentials.uid}")
                socket.close()
            }
            return null
        } finally {
            watchdog.interrupt()
        }
    }

    private fun configFromFormat(format: MediaFormat): VideoFrame? {
        val sps = format.getByteBuffer("csd-0") ?: return null
        val pps = format.getByteBuffer("csd-1")
        val bytes = ByteArray(sps.remaining() + (pps?.remaining() ?: 0))
        sps.duplicate().get(bytes, 0, sps.remaining())
        pps?.duplicate()?.get(bytes, sps.remaining(), pps.remaining())
        return VideoFrame(bytes, 0, keyFrame = false, config = true)
    }

    override fun input(event: RemoteInput) = send { out ->
        when (event) {
            is RemoteInput.Key -> RemoteInputWire.writeKey(out, event.down, event.keyCode, event.metaState, event.repeat)
            is RemoteInput.Pointer -> RemoteInputWire.writePointer(out, wireAction(event.action), event.x, event.y, event.pointerId, event.mouse, event.buttons)
            is RemoteInput.Scroll -> RemoteInputWire.writeScroll(out, event.x, event.y, event.horizontal, event.vertical)
        }
    }

    /** Writes one message to the program, in order, off the caller's thread. */
    private fun send(write: (DataOutputStream) -> Unit) {
        if (stopped.get()) return
        runCatching {
            writer.execute {
                val out = output ?: return@execute
                try {
                    synchronized(out) {
                        write(out)
                        out.flush()
                    }
                } catch (e: IOException) {
                    logger.debug(TAG, "Message not delivered: ${e.message}")
                }
            }
        }
    }

    private fun wireAction(action: PointerAction) = when (action) {
        PointerAction.DOWN -> RemoteInputWire.DOWN
        PointerAction.MOVE -> RemoteInputWire.MOVE
        PointerAction.UP -> RemoteInputWire.UP
        PointerAction.CANCEL -> RemoteInputWire.CANCEL
        PointerAction.HOVER -> RemoteInputWire.HOVER
    }

    override fun requestKeyFrame() {
        if (!stopped.get()) encoder.requestKeyFrame()
    }

    /** Ends the program (it finishes its activity when told, or when the socket closes). */
    override fun stop() {
        output?.let { out -> runCatching { synchronized(out) { out.writeByte(RemoteInputWire.QUIT); out.flush() } } }
        finish("Stopped")
    }

    private fun finish(reason: String) {
        if (!stopped.getAndSet(true)) {
            writer.shutdownNow()
            release()
        }
        if (!ended.getAndSet(true)) sink.onEnded(reason)
    }

    private fun release() {
        // The program's process ends when its connection closes (it finishes its activity).
        runCatching { client?.close() }
        runCatching { display.release() }
        // LocalServerSocket.accept() does not return when the socket closes: wake it up first.
        if (client == null) runCatching { LocalSocket().use { it.connect(LocalSocketAddress(socketName)) } }
        runCatching { server.close() }
        // Also when the program hangs or crashed: no task of it stays in the recent apps.
        runCatching { ProgramLauncher.removeProgramTasks(context) }
    }

    companion object {
        private const val TAG = "ProgramStream"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 60
        private const val START_TIMEOUT_MS = 15_000L

        private fun bitRate(width: Int, height: Int) = (width * height * FRAME_RATE * 0.08).toInt().coerceIn(2_000_000, 10_000_000)

        /**
         * The size closest to [width] x [height] that the encoder takes: rounded down to its
         * alignment, else to multiples of 16, made smaller until it is supported.
         */
        private fun encoderSize(capabilities: MediaCodecInfo.VideoCapabilities?, width: Int, height: Int): Pair<Int, Int> {
            if (capabilities == null) return width / 16 * 16 to height / 16 * 16
            val wa = capabilities.widthAlignment.coerceAtLeast(2)
            val ha = capabilities.heightAlignment.coerceAtLeast(2)
            var w = (width / wa * wa).coerceAtLeast(wa)
            var h = (height / ha * ha).coerceAtLeast(ha)
            if (capabilities.isSizeSupported(w, h)) return w to h
            w = (width / 16 * 16).coerceAtLeast(16)
            h = (height / 16 * 16).coerceAtLeast(16)
            while (!capabilities.isSizeSupported(w, h) && minOf(w, h) > 240) {
                w = (w * 3 / 4) / 16 * 16
                h = (h * 3 / 4) / 16 * 16
            }
            return w to h
        }

        /**
         * Opens the display and encoder at the largest size the encoder supports up to
         * [width] x [height]. [endReason] explains why the program ended, when it can.
         */
        fun open(
            context: Context,
            logger: Logger,
            width: Int,
            height: Int,
            sink: StreamSink,
            blackScreen: Boolean,
            endReason: () -> String?,
        ): AndroidProgramStream {
            val capabilities = runCatching {
                MediaCodec.createEncoderByType(MIME).let { codec ->
                    try { codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities } finally { codec.release() }
                }
            }.getOrNull()
            val (w, h) = encoderSize(capabilities, width, height)
            return AndroidProgramStream(context, logger, w, h, sink, endReason, blackScreen, capabilities)
        }
    }
}
