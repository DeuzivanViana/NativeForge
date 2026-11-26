package io.nativeforge.feature.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File
import kotlin.math.roundToInt

/**
 * Full-screen terminal backed by Termux's terminal emulator. Runs either a
 * compiled console program or an interactive shell, with a real PTY so that
 * programs reading stdin (scanf, std::cin, ncurses) behave as on a desktop.
 */
class ConsoleActivity : Activity() {

    private lateinit var terminalView: TerminalView
    private lateinit var session: TerminalSession
    private lateinit var extraKeys: ExtraKeysBar
    private var ctrlActive = false
        set(value) {
            field = value
            if (::extraKeys.isInitialized) extraKeys.setModifiers(value, altActive)
        }
    private var altActive = false
        set(value) {
            field = value
            if (::extraKeys.isInitialized) extraKeys.setModifiers(ctrlActive, value)
        }
    /** Whole sp, as in Settings; pinch zoom steps it by one. */
    private var fontSizeSp = ConsoleRequest.DEFAULT_FONT_SIZE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ConsoleContract.parse(intent)
        title = request.title
        fontSizeSp = (request.fontSizeSp ?: ConsoleRequest.DEFAULT_FONT_SIZE).coerceIn(ConsoleRequest.FONT_SIZES)

        terminalView = TerminalView(this, null).apply {
            setTextSize(fontSizePx(fontSizeSp))
            setBackgroundColor(android.graphics.Color.BLACK)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
            setTerminalViewClient(ViewClient())
        }
        session = createSession(request)
        terminalView.attachSession(session)

        extraKeys = ExtraKeysBar(
            this,
            onToggleCtrl = { ctrlActive = !ctrlActive },
            onToggleAlt = { altActive = !altActive },
            onKey = ::sendExtraKey,
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(terminalView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(extraKeys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        fitSystemBarsAndKeyboard(root)
        terminalView.requestFocus()
    }

    /**
     * Exported apps target a current API level, where Android 15 draws behind the system bars
     * and stops resizing for the keyboard: from Android 11 on, pad for both explicitly. Older
     * versions keep the default layout inside the bars and the manifest's adjustResize.
     */
    private fun fitSystemBarsAndKeyboard(root: ViewGroup) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        window.setDecorFitsSystemWindows(false)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
    }

    private fun fontSizePx(sp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics).roundToInt()

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
            val message = "\r\n\u001b[2m[Process exited with code ${finishedSession.exitStatus}. Press Enter or back to close.]\u001b[0m\r\n"
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
            // Below one step, keep accumulating; after a step (or at either end), start afresh.
            if (!TerminalFontSize.isStep(scale)) return scale
            val size = TerminalFontSize.afterPinch(fontSizeSp, scale)
            if (size != fontSizeSp) {
                fontSizeSp = size
                terminalView.setTextSize(fontSizePx(size))
                ConsoleContract.onFontSizeChanged?.invoke(size)
            }
            return 1.0f
        }

        override fun readControlKey(): Boolean = ctrlActive
        override fun readAltKey(): Boolean = altActive

        // Once the program has exited, Enter closes the console. Hardware keyboards and some
        // soft keyboards send a key event; others commit "\n", which arrives as a code point.
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean =
            (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && closeIfFinished(session)

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            // Sticky modifiers apply to a single key press, as on Termux's extra keys.
            ctrlActive = false
            altActive = false
            return (codePoint == '\r'.code || codePoint == '\n'.code) && closeIfFinished(session)
        }

        private fun closeIfFinished(session: TerminalSession): Boolean {
            if (session.isRunning) return false
            finish()
            return true
        }
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 4000
        val SYSTEM_VARIABLES = setOf("BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT")
        val DEFAULT_ENVIRONMENT = mapOf("TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8")
    }
}
