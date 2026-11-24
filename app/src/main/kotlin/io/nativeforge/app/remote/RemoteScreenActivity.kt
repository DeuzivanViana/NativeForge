package io.nativeforge.app.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nativeforge.app.NativeForgeApplication
import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.ui.theme.NativeForgeTheme
import kotlinx.coroutines.delay

/**
 * A full screen of pure black shown while remote access is on. On an OLED phone black pixels
 * are off, so the screen costs almost nothing, yet it stays on and unlocked: NativeForge is
 * the app in front, which is what Android requires before it lets the browser start
 * graphical programs. A tap shows the address and buttons for [AWAKE_MILLIS], then the
 * screen goes black again. Back leaves it; remote access keeps running.
 */
class RemoteScreenActivity : ComponentActivity() {

    private val manager get() = (application as NativeForgeApplication).container.remoteAccess

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        BlackScreen.hideSystemBars(this)
        setContent {
            val settings by (application as NativeForgeApplication).container.settings.settings.collectAsStateWithLifecycle()
            NativeForgeTheme(theme = settings.appTheme) {
                val state by manager.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.running, state.starting) {
                    if (!state.running && !state.starting) finish()
                }
                BlackScreen(onAwakeChanged = { awake -> BlackScreen.dim(this, !awake) }) {
                    AwakeContent(
                        state = state,
                        onStop = {
                            manager.stop()
                            finish()
                        },
                        onExit = ::finish,
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) BlackScreen.hideSystemBars(this)
    }

    companion object {
        const val AWAKE_MILLIS = 10_000L

        fun intent(context: Context): Intent = Intent(context, RemoteScreenActivity::class.java)
    }
}

@Composable
private fun AwakeContent(state: RemoteAccessState, onStop: () -> Unit, onExit: () -> Unit) {
    val dim = Color(0xFF9AA0A6)
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Remote access is on", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            state.primaryUrl ?: "Connect the phone to Wi-Fi or a VPN",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            "${state.connectedBrowsers} browser${if (state.connectedBrowsers == 1) "" else "s"} connected",
            color = dim,
            style = MaterialTheme.typography.bodySmall,
        )
        state.pairingCode?.let { code ->
            Text("Pairing code", color = dim, style = MaterialTheme.typography.labelSmall)
            Text(AccessControl.formatCode(code.code), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 26.sp, letterSpacing = 3.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExit) { Text("Leave black screen") }
            Button(onClick = onStop) { Text("Stop remote access") }
        }
        Text("Goes black again after 10 seconds. Tap to show this.", color = dim, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/**
 * Black, with [content] shown for [RemoteScreenActivity.AWAKE_MILLIS] after each touch.
 * Every touch (also on the content's buttons) restarts the timer.
 */
@Composable
internal fun BlackScreen(onAwakeChanged: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var awake by remember { mutableStateOf(false) }
    var touches by remember { mutableIntStateOf(0) }
    LaunchedEffect(awake) { onAwakeChanged(awake) }
    LaunchedEffect(touches) {
        if (touches == 0) return@LaunchedEffect
        delay(RemoteScreenActivity.AWAKE_MILLIS)
        awake = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // Seen before the buttons handle it, without consuming it.
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    awake = true
                    touches++
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (awake) content()
    }
}

internal object BlackScreen {
    /** Lowest backlight while black (LCD phones light black pixels too), the user's level while awake. */
    fun dim(activity: Activity, dark: Boolean) {
        val attributes = activity.window.attributes
        attributes.screenBrightness = if (dark) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = attributes
    }

    fun hideSystemBars(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
