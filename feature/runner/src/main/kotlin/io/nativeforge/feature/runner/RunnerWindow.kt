package io.nativeforge.feature.runner

import android.app.Activity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.nativeforge.core.project.runtime.LaunchSpec

/** Applies a launch spec's display options (orientation, full screen, keep screen on) to a host activity. */
internal object RunnerWindow {

    fun apply(activity: Activity, spec: LaunchSpec) {
        activity.requestedOrientation = spec.orientation.androidValue
        if (spec.keepScreenOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (spec.immersive) hideSystemBars(activity)
    }

    /** Hides the status and navigation bars; a swipe from the edge shows them for a moment. */
    fun hideSystemBars(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
