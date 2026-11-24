package io.nativeforge.core.remote.screen

import io.nativeforge.core.project.model.ExportSettings
import kotlin.math.roundToInt

/**
 * A graphical program running on an off-screen display whose picture is encoded as
 * H.264 and sent to browsers. The platform side (virtual display, encoder, input
 * injection) lives in the app; this module only moves frames and input.
 */
interface ProgramStream {
    /** Size of the off-screen display, in pixels; changes with [resize]. */
    val width: Int
    val height: Int

    /**
     * Changes the off-screen display to about [width] x [height] (the platform may round
     * it to what its encoder takes); the program sees its window resize.
     */
    fun resize(width: Int, height: Int) {}

    /** Delivers browser input to the program's window. */
    fun input(event: RemoteInput)

    /** Asks the encoder for a key frame soon (a viewer joined or fell behind). */
    fun requestKeyFrame()

    /** Ends the program and releases the display and encoder. */
    fun stop()
}

/** Where a [ProgramStream] sends its output. Called from the encoder's thread. */
interface StreamSink {
    fun onFrame(frame: VideoFrame)

    /** The program ended or the stream failed; [reason] is shown to the user. */
    fun onEnded(reason: String?)

    /**
     * The program asked for a window of [width] x [height] (SDL_CreateWindow, raylib's
     * InitWindow). A size of 0 keeps the current one. A [resizable] window follows the
     * browser's panel; any other keeps its size and the browser letterboxes it.
     */
    fun onWindowRequest(width: Int, height: Int, resizable: Boolean) {}
}

/**
 * One unit of H.264 in Annex B form. [config] units carry the SPS and PPS
 * (sent once by the encoder, then repeated before key frames for new viewers).
 */
class VideoFrame(val data: ByteArray, val ptsMicros: Long, val keyFrame: Boolean, val config: Boolean = false)

/** Browser input, already converted to Android's units (pixels, key codes, meta state). */
sealed interface RemoteInput {
    data class Key(val down: Boolean, val keyCode: Int, val metaState: Int, val repeat: Int = 0) : RemoteInput

    /** [buttons] uses Android's MotionEvent button bits (primary 1, secondary 2, tertiary 4). */
    data class Pointer(
        val action: PointerAction,
        val x: Float,
        val y: Float,
        val pointerId: Int,
        val mouse: Boolean,
        val buttons: Int,
    ) : RemoteInput

    /** Mouse wheel; positive [vertical] scrolls up, as in Android's AXIS_VSCROLL. */
    data class Scroll(val x: Float, val y: Float, val horizontal: Float, val vertical: Float) : RemoteInput
}

enum class PointerAction { DOWN, MOVE, UP, CANCEL, HOVER }

/** The off-screen display's size for a browser panel of [width] x [height] pixels. */
object StreamSize {
    const val MAX_LONG_SIDE = 1920
    const val MAX_SHORT_SIDE = 1080
    const val MIN_SIDE = 240

    /**
     * Keeps the panel's shape, turns it to the program's fixed orientation when it has
     * one, fits it in 1080p and rounds both sides to a multiple of 16 (what hardware
     * encoders handle everywhere).
     */
    fun fit(width: Int, height: Int, orientation: ExportSettings.Orientation): Pair<Int, Int> {
        var w = width.coerceAtLeast(MIN_SIDE).toDouble()
        var h = height.coerceAtLeast(MIN_SIDE).toDouble()
        val wantsLandscape = orientation == ExportSettings.Orientation.LANDSCAPE || orientation == ExportSettings.Orientation.SENSOR_LANDSCAPE
        val wantsPortrait = orientation == ExportSettings.Orientation.PORTRAIT || orientation == ExportSettings.Orientation.SENSOR_PORTRAIT
        if ((wantsLandscape && h > w) || (wantsPortrait && w > h)) w = h.also { h = w }
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val scale = minOf(1.0, MAX_LONG_SIDE / long, MAX_SHORT_SIDE / short)
        w *= scale
        h *= scale
        return align(w) to align(h)
    }

    /**
     * The display size for a window the program asked for: its own size and shape, made
     * smaller to fit 1080p (or larger when a side is under [MIN_SIDE]), with even sides.
     */
    fun program(width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceAtLeast(1).toDouble()
        val h = height.coerceAtLeast(1).toDouble()
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val scale = minOf(MAX_LONG_SIDE / long, MAX_SHORT_SIDE / short).let { down ->
            if (down < 1.0) down else maxOf(1.0, minOf(MIN_SIDE / short, MAX_LONG_SIDE / long))
        }
        return even(w * scale) to even(h * scale)
    }

    private fun align(value: Double) = ((value / 16).roundToInt() * 16).coerceAtLeast(MIN_SIDE)

    private fun even(value: Double) = ((value / 2).roundToInt() * 2).coerceAtLeast(2)
}

/** Reads what WebCodecs needs from the encoder's codec configuration. */
object H264 {
    /** The `avc1.PPCCLL` codec string from the SPS in [config] (Annex B), or null. */
    fun codecString(config: ByteArray): String? {
        val sps = nalUnits(config).firstOrNull { (start, _) -> config[start].toInt() and 0x1f == 7 } ?: return null
        val (start, end) = sps
        if (end - start < 4) return null
        return "avc1.%02x%02x%02x".format(config[start + 1].toInt() and 0xff, config[start + 2].toInt() and 0xff, config[start + 3].toInt() and 0xff)
    }

    /** Start and end offsets of each NAL unit's payload (after its start code). */
    fun nalUnits(data: ByteArray): List<Pair<Int, Int>> {
        val starts = ArrayList<Pair<Int, Int>>() // payload start, start-code start
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts += (i + 3) to i
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += (i + 4) to i
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { index, (payload, _) ->
            val end = if (index + 1 < starts.size) starts[index + 1].second else data.size
            payload to end
        }.filter { (start, end) -> start < end }
    }
}

/**
 * Turns browser keyboard events (KeyboardEvent.code and .key) into Android key codes.
 * Letters follow the PC's layout (an AZERTY "A" is KEYCODE_A); every other key goes by
 * its physical position, like a USB keyboard plugged into the phone.
 */
object KeyMap {
    private const val KEYCODE_A = 29
    private const val KEYCODE_0 = 7
    private const val KEYCODE_F1 = 131
    private const val KEYCODE_NUMPAD_0 = 144

    private val byCode: Map<String, Int> = buildMap {
        for (i in 0..25) put("Key${'A' + i}", KEYCODE_A + i)
        for (i in 0..9) put("Digit$i", KEYCODE_0 + i)
        for (i in 0..9) put("Numpad$i", KEYCODE_NUMPAD_0 + i)
        for (i in 1..12) put("F$i", KEYCODE_F1 + i - 1)
        putAll(
            mapOf(
                "Comma" to 55, "Period" to 56, "AltLeft" to 57, "AltRight" to 58, "ShiftLeft" to 59, "ShiftRight" to 60,
                "Tab" to 61, "Space" to 62, "Enter" to 66, "Backspace" to 67, "Backquote" to 68, "Minus" to 69,
                "Equal" to 70, "BracketLeft" to 71, "BracketRight" to 72, "Backslash" to 73, "Semicolon" to 74,
                "Quote" to 75, "Slash" to 76, "ArrowUp" to 19, "ArrowDown" to 20, "ArrowLeft" to 21, "ArrowRight" to 22,
                "Escape" to 111, "Delete" to 112, "ControlLeft" to 113, "ControlRight" to 114, "CapsLock" to 115,
                "ScrollLock" to 116, "MetaLeft" to 117, "MetaRight" to 118, "PrintScreen" to 120, "Pause" to 121,
                "Home" to 122, "End" to 123, "Insert" to 124, "PageUp" to 92, "PageDown" to 93, "ContextMenu" to 82,
                "NumLock" to 143, "NumpadDivide" to 154, "NumpadMultiply" to 155, "NumpadSubtract" to 156,
                "NumpadAdd" to 157, "NumpadDecimal" to 158, "NumpadComma" to 159, "NumpadEnter" to 160,
                "NumpadEqual" to 161, "IntlBackslash" to 73, "IntlRo" to 217, "IntlYen" to 216,
            ),
        )
    }

    /** The Android key code, or null for keys Android has no code for. */
    fun keyCode(code: String?, key: String?): Int? {
        if (key != null && key.length == 1) {
            val c = key[0].lowercaseChar()
            if (c in 'a'..'z') return KEYCODE_A + (c - 'a')
        }
        return code?.let(byCode::get)
    }

    const val MOD_SHIFT = 1
    const val MOD_CTRL = 2
    const val MOD_ALT = 4
    const val MOD_META = 8
    const val MOD_CAPS = 16

    /** Android's meta state for the browser's modifier bits ([MOD_SHIFT] and friends). */
    fun metaState(modifiers: Int): Int {
        var meta = 0
        if (modifiers and MOD_SHIFT != 0) meta = meta or 0x1 or 0x40
        if (modifiers and MOD_CTRL != 0) meta = meta or 0x1000 or 0x2000
        if (modifiers and MOD_ALT != 0) meta = meta or 0x2 or 0x10
        if (modifiers and MOD_META != 0) meta = meta or 0x10000 or 0x20000
        if (modifiers and MOD_CAPS != 0) meta = meta or 0x100000
        return meta
    }
}
