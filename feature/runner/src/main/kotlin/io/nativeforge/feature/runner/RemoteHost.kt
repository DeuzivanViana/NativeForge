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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Where a streamed run shows: an off-screen display of the IDE and the socket its input
 * comes from. With [blackScreen] the phone stays on and unlocked behind a black screen.
 */
data class RemoteRun(val displayId: Int, val inputSocket: String, val blackScreen: Boolean = false)

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
internal class RemoteHost private constructor(private val activity: Activity, private val run: RemoteRun, display: android.view.Display) {
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

    /**
     * What the phone shows while the program runs in the browser. With a black screen the
     * note appears for 10 seconds after a tap; the screen stays on and unlocked (so the next
     * run can start) while its OLED pixels stay off.
     */
    fun placeholder(): View {
        val note = TextView(activity).apply {
            text = "Running in the browser"
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(note, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        if (!run.blackScreen) return root
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        RunnerWindow.hideSystemBars(activity)
        val sleep = Runnable {
            note.visibility = View.INVISIBLE
            setBrightness(0.01f)
        }
        sleep.run()
        root.setOnClickListener {
            note.visibility = View.VISIBLE
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            root.removeCallbacks(sleep)
            root.postDelayed(sleep, AWAKE_MILLIS)
        }
        return root
    }

    private fun setBrightness(value: Float) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = value
        activity.window.attributes = attributes
    }

    fun close() {
        input.close()
        runCatching { presentation.dismiss() }
    }

    companion object {
        private const val TAG = "RemoteHost"
        private const val EXTRA_DISPLAY = "io.nativeforge.runner.REMOTE_DISPLAY"
        private const val EXTRA_SOCKET = "io.nativeforge.runner.REMOTE_INPUT_SOCKET"
        private const val EXTRA_BLACK = "io.nativeforge.runner.REMOTE_BLACK_SCREEN"
        private const val AWAKE_MILLIS = 10_000L

        fun putInto(intent: Intent, run: RemoteRun): Intent =
            intent.putExtra(EXTRA_DISPLAY, run.displayId).putExtra(EXTRA_SOCKET, run.inputSocket).putExtra(EXTRA_BLACK, run.blackScreen)

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
            return RemoteHost(activity, RemoteRun(displayId, socket, intent.getBooleanExtra(EXTRA_BLACK, false)), display)
        }
    }
}
