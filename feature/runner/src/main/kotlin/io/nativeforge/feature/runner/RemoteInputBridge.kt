package io.nativeforge.feature.runner

import android.app.Activity
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Feeds keyboard, mouse and touch input from the remote web IDE into the program's
 * window on the off-screen display (see [RemoteHost]).
 *
 * The IDE process listens on an abstract local socket whose name comes in the launch
 * intent; it only accepts this app's own user id. Events are injected with
 * [Window.injectInputEvent], which runs them through the window's normal input
 * pipeline: SDL's views get them like a USB keyboard and mouse, and NativeActivity
 * forwards them to the program's AInputQueue.
 */
internal class RemoteInputBridge(
    private val activity: Activity,
    private val window: Window,
    private val socketName: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val socket = LocalSocket()
    @Volatile private var closed = false

    /** Pointers currently down on the touch screen, by id (multi-touch). */
    private val touches = LinkedHashMap<Int, Pair<Float, Float>>()
    private var touchDownTime = 0L
    private var mouseDownTime = 0L
    private var mouseButtons = 0
    private val keyDownTimes = HashMap<Int, Long>()

    private val thread = Thread(::run, "nf-remote-input").apply { isDaemon = true }

    private fun run() {
        try {
            socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
            val output = DataOutputStream(socket.outputStream)
            @Suppress("DEPRECATION")
            output.writeInt(window.decorView.display?.displayId ?: -1)
            output.flush()
            val input = DataInputStream(socket.inputStream.buffered())
            while (!closed) {
                when (val type = input.readByte().toInt()) {
                    RemoteInputWire.KEY -> {
                        val down = input.readBoolean()
                        val keyCode = input.readInt()
                        val meta = input.readInt()
                        val repeat = input.readInt()
                        main.post { key(down, keyCode, meta, repeat) }
                    }
                    RemoteInputWire.POINTER -> {
                        val action = input.readByte().toInt()
                        val x = input.readFloat()
                        val y = input.readFloat()
                        val id = input.readInt()
                        val mouse = input.readBoolean()
                        val buttons = input.readInt()
                        main.post { if (mouse) mouse(action, x, y, buttons) else touch(action, x, y, id) }
                    }
                    RemoteInputWire.SCROLL -> {
                        val x = input.readFloat()
                        val y = input.readFloat()
                        val horizontal = input.readFloat()
                        val vertical = input.readFloat()
                        main.post { scroll(x, y, horizontal, vertical) }
                    }
                    RemoteInputWire.QUIT -> main.post { activity.finish() }
                    else -> throw IOException("Unknown input message $type")
                }
            }
        } catch (e: IOException) {
            // The IDE stopped the stream (or its process went away): end the program too.
            if (!closed) main.post { if (!activity.isFinishing) activity.finish() }
        } catch (e: Exception) {
            Log.w(TAG, "Remote input stopped", e)
        }
    }

    /**
     * Off-screen displays rarely get input focus from the system. Without focus a window
     * drops key events, and programs pause (raylib stops drawing until it regains focus),
     * so the window gets focus locally: only its own view of the focus changes.
     */
    private fun keepFocus() {
        val decor = window.decorView
        decor.post(::ensureFocus)
        decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus -> if (!hasFocus && !closed) decor.post(::ensureFocus) }
    }

    private fun ensureFocus() {
        if (closed || activity.isFinishing || window.decorView.hasWindowFocus()) return
        runCatching { window.setLocalFocus(true, false) }.onFailure { Log.w(TAG, "Could not focus the window", it) }
    }

    private fun inject(event: android.view.InputEvent) {
        if (activity.isFinishing) return
        ensureFocus()
        runCatching { window.injectInputEvent(event) }.onFailure { Log.w(TAG, "Could not inject $event", it) }
    }

    private fun key(down: Boolean, keyCode: Int, meta: Int, repeat: Int) {
        val now = SystemClock.uptimeMillis()
        val downTime = if (down) keyDownTimes.getOrPut(keyCode) { now } else keyDownTimes.remove(keyCode) ?: now
        val event = KeyEvent(
            downTime, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, repeat, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        inject(event)
    }

    private fun touch(action: Int, x: Float, y: Float, id: Int) {
        val now = SystemClock.uptimeMillis()
        val masked = when (action) {
            RemoteInputWire.DOWN -> {
                if (touches.isEmpty()) touchDownTime = now
                touches[id] = x to y
                if (touches.size == 1) MotionEvent.ACTION_DOWN else pointerAction(MotionEvent.ACTION_POINTER_DOWN, id)
            }
            RemoteInputWire.MOVE -> {
                if (id !in touches) return
                touches[id] = x to y
                MotionEvent.ACTION_MOVE
            }
            RemoteInputWire.UP -> {
                if (id !in touches) return
                touches[id] = x to y
                if (touches.size == 1) MotionEvent.ACTION_UP else pointerAction(MotionEvent.ACTION_POINTER_UP, id)
            }
            RemoteInputWire.CANCEL -> if (touches.isEmpty()) return else MotionEvent.ACTION_CANCEL
            else -> return
        }
        val ids = touches.keys.toList()
        val properties = ids.map { pointerId -> MotionEvent.PointerProperties().apply { this.id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = ids.map { pointerId ->
            val (px, py) = touches.getValue(pointerId)
            MotionEvent.PointerCoords().apply { this.x = px; this.y = py; pressure = 1f; size = 1f }
        }
        inject(
            MotionEvent.obtain(
                touchDownTime, now, masked, ids.size, properties.toTypedArray(), coords.toTypedArray(),
                0, 0, 1f, 1f, VIRTUAL_DEVICE, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            ),
        )
        if (action == RemoteInputWire.UP) touches.remove(id)
        if (action == RemoteInputWire.CANCEL) touches.clear()
    }

    private fun pointerAction(base: Int, id: Int) = base or (touches.keys.indexOf(id) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private fun mouse(action: Int, x: Float, y: Float, buttons: Int) {
        val now = SystemClock.uptimeMillis()
        val masked = when (action) {
            RemoteInputWire.HOVER -> MotionEvent.ACTION_HOVER_MOVE
            RemoteInputWire.DOWN -> if (mouseButtons == 0) MotionEvent.ACTION_DOWN.also { mouseDownTime = now } else MotionEvent.ACTION_MOVE
            RemoteInputWire.MOVE -> if (mouseButtons == 0) MotionEvent.ACTION_HOVER_MOVE else MotionEvent.ACTION_MOVE
            RemoteInputWire.UP -> if (buttons == 0 && mouseButtons != 0) MotionEvent.ACTION_UP else if (mouseButtons == 0) return else MotionEvent.ACTION_MOVE
            RemoteInputWire.CANCEL -> if (mouseButtons == 0) return else MotionEvent.ACTION_CANCEL
            else -> return
        }
        mouseButtons = if (action == RemoteInputWire.CANCEL) 0 else buttons
        val hover = masked == MotionEvent.ACTION_HOVER_MOVE
        inject(mouseEvent(if (hover) now else mouseDownTime, now, masked, x, y, if (hover) 0 else mouseButtons))
    }

    private fun scroll(x: Float, y: Float, horizontal: Float, vertical: Float) {
        val now = SystemClock.uptimeMillis()
        inject(mouseEvent(now, now, MotionEvent.ACTION_SCROLL, x, y, mouseButtons) { coords ->
            coords.setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal)
            coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vertical)
        })
    }

    private inline fun mouseEvent(
        downTime: Long,
        now: Long,
        action: Int,
        x: Float,
        y: Float,
        buttons: Int,
        axes: (MotionEvent.PointerCoords) -> Unit = {},
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            axes(this)
        }
        return MotionEvent.obtain(
            downTime, now, action, 1, arrayOf(properties), arrayOf(coords),
            0, buttons, 1f, 1f, VIRTUAL_DEVICE, 0, InputDevice.SOURCE_MOUSE, 0,
        )
    }

    fun close() {
        closed = true
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    /** Connects to the IDE and starts delivering its input; call once the window is shown. */
    fun start() {
        keepFocus()
        thread.start()
    }

    private companion object {
        const val TAG = "RemoteInput"
        const val VIRTUAL_DEVICE = KeyCharacterMap.VIRTUAL_KEYBOARD
    }
}

/** Messages on the input socket: a type byte, then the fields in [java.io.DataOutput] form. */
object RemoteInputWire {
    const val KEY = 1 // down: boolean, keyCode: int, metaState: int, repeat: int
    const val POINTER = 2 // action: byte, x: float, y: float, id: int, mouse: boolean, buttons: int
    const val SCROLL = 3 // x, y, horizontal, vertical: float
    const val QUIT = 9

    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2
    const val CANCEL = 3
    const val HOVER = 4

    fun writeKey(out: DataOutputStream, down: Boolean, keyCode: Int, metaState: Int, repeat: Int) {
        out.writeByte(KEY); out.writeBoolean(down); out.writeInt(keyCode); out.writeInt(metaState); out.writeInt(repeat)
    }

    fun writePointer(out: DataOutputStream, action: Int, x: Float, y: Float, id: Int, mouse: Boolean, buttons: Int) {
        out.writeByte(POINTER); out.writeByte(action); out.writeFloat(x); out.writeFloat(y); out.writeInt(id); out.writeBoolean(mouse); out.writeInt(buttons)
    }

    fun writeScroll(out: DataOutputStream, x: Float, y: Float, horizontal: Float, vertical: Float) {
        out.writeByte(SCROLL); out.writeFloat(x); out.writeFloat(y); out.writeFloat(horizontal); out.writeFloat(vertical)
    }
}
