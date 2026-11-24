package io.nativeforge.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import io.nativeforge.core.ui.theme.NativeForgeTheme
import java.io.File

/**
 * Full-screen terminal backed by Termux's terminal emulator. Runs either a
 * compiled console program or an interactive shell, with a real PTY so that
 * programs reading stdin (scanf, std::cin, ncurses) behave as on a desktop.
 */
class ConsoleActivity : ComponentActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession
    private var ctrlActive by mutableStateOf(false)
    private var altActive by mutableStateOf(false)
    private var fontSizePx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ConsoleContract.parse(intent)
        title = request.title
        val fontSp = request.fontSizeSp?.toFloat() ?: DEFAULT_FONT_SP
        fontSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp, resources.displayMetrics).toInt()

        terminalView = TerminalView(this, null).apply {
            setTextSize(fontSizePx)
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(ViewClient())
        }
        session = createSession(request)
        terminalView.attachSession(session)

        setContent {
            NativeForgeTheme {
                Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().imePadding()) {
                    AndroidView(factory = { terminalView }, modifier = Modifier.weight(1f).fillMaxWidth())
                    ExtraKeysBar(
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        onKey = ::sendExtraKey,
                    )
                }
            }
        }
        terminalView.requestFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::session.isInitialized) session.finishIfRunning()
    }

    private fun createSession(request: ConsoleRequest): TerminalSession {
        val executable = request.executable ?: request.shell
        File(request.workingDirectory).mkdirs()
        // argv[0] is part of the argument array passed to the PTY helper.
        val argv = (listOf(File(executable).name) + request.arguments).toTypedArray()
        val environment = (systemEnvironment() + DEFAULT_ENVIRONMENT + request.environment).map { (k, v) -> "$k=$v" }.toTypedArray()
        return TerminalSession(executable, request.workingDirectory, argv, environment, TRANSCRIPT_ROWS, SessionClient())
    }

    /**
     * The session gets only the variables passed here, so keep the ones Android's own tools
     * need: `app_process` (used by `pkg` and Termux's `am`) fails without the class paths
     * and ANDROID_* roots, and TLS needs ANDROID_ROOT to find the system certificates.
     */
    private fun systemEnvironment(): Map<String, String> =
        System.getenv().filterKeys { it.startsWith("ANDROID_") || it in SYSTEM_VARIABLES }

    private fun sendExtraKey(key: ExtraKey) {
        when (key) {
            is ExtraKey.Text -> session.write(key.text)
            is ExtraKey.Code -> {
                val emulator = session.emulator ?: return
                val code = KeyHandler.getCode(key.keyCode, 0, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
                code?.let(session::write)
            }
        }
        terminalView.requestFocus()
    }

    private fun showKeyboard() {
        terminalView.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private inner class SessionClient : TerminalSessionClientBase() {
        override fun onTextChanged(changedSession: TerminalSession) = terminalView.onScreenUpdated()

        override fun onTitleChanged(changedSession: TerminalSession) {
            changedSession.title?.let { title = it }
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val message = "\r\n\u001b[2m[Process exited with code ${finishedSession.exitStatus}. Press back to close.]\u001b[0m\r\n"
            val bytes = message.toByteArray()
            finishedSession.emulator?.append(bytes, bytes.size)
            terminalView.onScreenUpdated()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text.orEmpty()))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@ConsoleActivity)?.toString()
            if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
        }
    }

    private inner class ViewClient : TerminalViewClientBase() {
        override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                val factor = if (scale > 1f) 1.1f else 0.9f
                fontSizePx = (fontSizePx * factor).toInt().coerceIn(MIN_FONT_PX, MAX_FONT_PX)
                terminalView.setTextSize(fontSizePx)
                return 1.0f
            }
            return scale
        }

        override fun readControlKey(): Boolean = ctrlActive
        override fun readAltKey(): Boolean = altActive

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // Sticky modifiers apply to a single key press, as on Termux's extra keys.
            ctrlActive = false
            altActive = false
            return false
        }
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 4000
        const val DEFAULT_FONT_SP = 12f
        const val MIN_FONT_PX = 12
        const val MAX_FONT_PX = 96
        val SYSTEM_VARIABLES = setOf("BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT")
        val DEFAULT_ENVIRONMENT = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8")
    }
}
