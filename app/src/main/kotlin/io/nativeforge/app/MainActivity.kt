package io.nativeforge.app

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nativeforge.app.ui.navigation.NativeForgeNavHost
import io.nativeforge.core.ui.theme.NativeForgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = (application as NativeForgeApplication).container.settings
        applySystemBars(settingsRepository.settings.value.appTheme.palette.isDark)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            val dark = settings.appTheme.palette.isDark
            LaunchedEffect(dark) { applySystemBars(dark) }
            NativeForgeTheme(theme = settings.appTheme) {
                NativeForgeNavHost()
            }
        }
    }

    /** Physical keyboard shortcuts (Ctrl+S, F5...) reach the workspace before the editor view. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        io.nativeforge.app.ui.workspace.commands.KeyboardShortcuts.dispatch(event) || super.dispatchKeyEvent(event)

    /** Ctrl + mouse wheel zooms the editor, as in desktop editors. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && event.metaState and KeyEvent.META_CTRL_ON != 0) {
            val delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) {
                val settings = (application as NativeForgeApplication).container.settings
                settings.update { s ->
                    val size = (s.editor.fontSizeSp + if (delta > 0) 1 else -1)
                        .coerceIn(io.nativeforge.app.settings.AppSettings.FONT_SIZES.first.toFloat(), io.nativeforge.app.settings.AppSettings.FONT_SIZES.last.toFloat())
                    s.copy(editor = s.editor.copy(fontSizeSp = size))
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** System bar icons follow the app theme, not the phone's light/dark mode. */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
