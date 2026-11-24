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
 * program's process reads browser input from.
 *
 * The program itself is started by the caller with [run]; it draws on [display] through a
 * Presentation (see the runner's RemoteHost).
 */
class AndroidProgramStream private constructor(
    private val context: Context,
    private val logger: Logger,
    override val width: Int,
    override val height: Int,
    private val sink: StreamSink,
    /** Reason shown when the program's process goes away (its start error, if it wrote one). */
    private val endReason: () -> String?,
    /** Keep the phone on behind a black screen while the program runs (see RemoteScreenActivity). */
    private val blackScreen: Boolean,
) : ProgramStream {
    private val socketName = "nativeforge.remote-input." + ByteArray(12).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val server = LocalServerSocket(socketName)
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val surface: Surface
    private val display: VirtualDisplay
    private val stopped = AtomicBoolean()
    private val ended = AtomicBoolean()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "nf-stream-input").apply { isDaemon = true } }
    @Volatile private var client: LocalSocket? = null
    @Volatile private var output: DataOutputStream? = null

    val run: RemoteRun

    init {
        try {
            codec.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            val metrics = context.resources.displayMetrics
            // Same physical size of things as on the phone, scaled to the display's size.
            val phoneShortSide = minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
            val dpi = (metrics.densityDpi * minOf(width, height) / phoneShortSide.toFloat()).roundToInt().coerceIn(120, 640)
            display = context.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay("NativeForge remote", width, height, dpi, surface, 0)
                ?: throw IllegalStateException("Android did not create the off-screen display")
        } catch (e: Exception) {
            runCatching { codec.release() }
            runCatching { server.close() }
            throw e
        }
        run = RemoteRun(display.display.displayId, socketName, blackScreen)
        Thread(::drain, "nf-stream-encoder").apply { isDaemon = true }.start()
        Thread(::serve, "nf-stream-socket").apply { isDaemon = true }.start()
    }

    private fun format() = MediaFormat.createVideoFormat(MIME, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate(width, height))
        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        // Keep sending frames while the picture stands still, so new viewers get one soon.
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }

    /** Copies encoded frames to the sink until the stream stops, then releases the encoder. */
    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (!stopped.get()) {
                val index = codec.dequeueOutputBuffer(info, 100_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> configFromFormat(codec.outputFormat)?.let(sink::onFrame)
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
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
            if (!stopped.get()) {
                logger.error(TAG, "Video encoder failed", e)
                finish("The video encoder failed: ${e.message}")
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { surface.release() }
        }
    }

    /** Waits for the program's process, then watches its connection; when it closes, the program ended. */
    private fun serve() {
        try {
            val socket = acceptOwnProcess() ?: return
            client = socket
            output = DataOutputStream(BufferedOutputStream(socket.outputStream))
            val input = DataInputStream(socket.inputStream)
            input.readInt() // the display the program is on; its arrival means the program is up
            logger.info(TAG, "Streamed program connected")
            while (input.read() >= 0) Unit
        } catch (_: IOException) {
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

    override fun input(event: RemoteInput) {
        if (stopped.get()) return
        writer.execute {
            val out = output ?: return@execute
            try {
                synchronized(out) {
                    when (event) {
                        is RemoteInput.Key -> RemoteInputWire.writeKey(out, event.down, event.keyCode, event.metaState, event.repeat)
                        is RemoteInput.Pointer -> RemoteInputWire.writePointer(out, wireAction(event.action), event.x, event.y, event.pointerId, event.mouse, event.buttons)
                        is RemoteInput.Scroll -> RemoteInputWire.writeScroll(out, event.x, event.y, event.horizontal, event.vertical)
                    }
                    out.flush()
                }
            } catch (e: IOException) {
                logger.debug(TAG, "Input not delivered: ${e.message}")
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
        if (stopped.get()) return
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
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
    }

    companion object {
        private const val TAG = "ProgramStream"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FRAME_RATE = 60
        private const val START_TIMEOUT_MS = 15_000L

        private fun bitRate(width: Int, height: Int) = (width * height * FRAME_RATE * 0.08).toInt().coerceIn(2_000_000, 10_000_000)

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
            var (w, h) = width to height
            val capabilities = runCatching {
                MediaCodec.createEncoderByType(MIME).let { codec ->
                    try { codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities } finally { codec.release() }
                }
            }.getOrNull()
            while (capabilities != null && !capabilities.isSizeSupported(w, h) && minOf(w, h) > 240) {
                w = (w * 3 / 4) / 16 * 16
                h = (h * 3 / 4) / 16 * 16
            }
            return AndroidProgramStream(context, logger, w, h, sink, endReason, blackScreen)
        }
    }
}
