package io.nativeforge.feature.runner

import android.app.Activity
import android.app.Presentation
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Where a streamed run shows: an off-screen display of the IDE and the socket its input comes from. */
data class RemoteRun(val displayId: Int, val inputSocket: String)

/**
 * A run shown in the remote web IDE instead of on the phone.
 *
 * Android lets an app launch activities only on displays it may embed into, which an
 * app's own virtual display is not. So the host activity stays on the phone's display
 * (showing a short note) and the program draws into a [Presentation]: a window the app
 * may place on its own private virtual display, whose picture the IDE encodes. The
 * presentation is made from the application context, so it does not stop with the
 * activity when the screen turns off.
 */
internal class RemoteHost private constructor(private val activity: Activity, run: RemoteRun, display: android.view.Display) {
    val presentation = Presentation(activity.applicationContext, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
        setCancelable(false)
    }
    private val input = RemoteInputBridge(activity, presentation.window!!, run.inputSocket)
    private var shown = false

    /** Shows the presentation (with [content], if given) and starts taking input. */
    fun show(content: View? = null) {
        if (shown) return
        shown = true
        content?.let(presentation::setContentView)
        presentation.show()
        input.start()
    }

    /** What the phone shows while the program runs in the browser. */
    fun placeholder(): View = TextView(activity).apply {
        text = "Running in the browser"
        gravity = Gravity.CENTER
        setTextColor(Color.LTGRAY)
        setBackgroundColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    fun close() {
        input.close()
        runCatching { presentation.dismiss() }
    }

    companion object {
        private const val TAG = "RemoteHost"
        private const val EXTRA_DISPLAY = "io.nativeforge.runner.REMOTE_DISPLAY"
        private const val EXTRA_SOCKET = "io.nativeforge.runner.REMOTE_INPUT_SOCKET"

        fun putInto(intent: Intent, run: RemoteRun): Intent =
            intent.putExtra(EXTRA_DISPLAY, run.displayId).putExtra(EXTRA_SOCKET, run.inputSocket)

        /** The remote host for [activity], or null when it runs on the phone's screen. */
        fun fromIntent(activity: Activity): RemoteHost? {
            val intent = activity.intent ?: return null
            val displayId = intent.getIntExtra(EXTRA_DISPLAY, -1)
            val socket = intent.getStringExtra(EXTRA_SOCKET)
            if (displayId < 0 || socket == null) return null
            val display = activity.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            if (display == null) {
                // The IDE closed the display already (the browser stopped the run).
                Log.w(TAG, "Display $displayId is gone")
                activity.finish()
                return null
            }
            return RemoteHost(activity, RemoteRun(displayId, socket), display)
        }
    }
}
