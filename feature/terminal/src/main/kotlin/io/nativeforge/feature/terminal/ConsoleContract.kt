package io.nativeforge.feature.terminal

import android.content.Context
import android.content.Intent

/** What to run inside [ConsoleActivity]. A null [executable] starts an interactive [shell]. */
data class ConsoleRequest(
    val title: String,
    val workingDirectory: String,
    val executable: String? = null,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val shell: String = DEFAULT_SHELL,
    /** Terminal font size; null uses the default. */
    val fontSizeSp: Int? = null,
) {
    companion object {
        const val DEFAULT_SHELL = "/system/bin/sh"
        const val DEFAULT_FONT_SIZE = 12

        /** Terminal font sizes in whole sp, for Settings and pinch zoom alike. */
        val FONT_SIZES = 8..24
    }
}

/** Terminal pinch zoom: one whole sp per step, so the size saved in Settings is the size drawn. */
object TerminalFontSize {
    /** How far a pinch must spread or close before the font changes by one step. */
    private const val STEP_SCALE = 0.1f

    /** Whether a pinch of [scale] (accumulated since the last step) is enough for one step. */
    fun isStep(scale: Float): Boolean = scale > 1f + STEP_SCALE || scale < 1f - STEP_SCALE

    /** The size after a pinch of [scale]: one step up or down, or [sp] when the pinch is too small. */
    fun afterPinch(sp: Int, scale: Float): Int = when {
        !isStep(scale) -> sp
        scale > 1f -> sp + 1
        else -> sp - 1
    }.coerceIn(ConsoleRequest.FONT_SIZES)
}

/** Intent (de)serialization for [ConsoleActivity]. */
object ConsoleContract {
    private const val EXTRA_TITLE = "io.nativeforge.console.TITLE"
    private const val EXTRA_CWD = "io.nativeforge.console.CWD"
    private const val EXTRA_EXECUTABLE = "io.nativeforge.console.EXECUTABLE"
    private const val EXTRA_ARGS = "io.nativeforge.console.ARGS"
    private const val EXTRA_ENV = "io.nativeforge.console.ENV"
    private const val EXTRA_SHELL = "io.nativeforge.console.SHELL"
    private const val EXTRA_FONT_SIZE = "io.nativeforge.console.FONT_SIZE"

    /**
     * Told the new size (whole sp) after each pinch zoom step. The IDE saves it in Settings so the
     * value there matches the terminal; exported apps leave it unset.
     */
    @Volatile var onFontSizeChanged: ((Int) -> Unit)? = null

    fun intent(context: Context, request: ConsoleRequest): Intent =
        Intent(context, ConsoleActivity::class.java)
            .putExtra(EXTRA_TITLE, request.title)
            .putExtra(EXTRA_CWD, request.workingDirectory)
            .putExtra(EXTRA_EXECUTABLE, request.executable)
            .putExtra(EXTRA_ARGS, request.arguments.toTypedArray())
            .putExtra(EXTRA_ENV, request.environment.map { (k, v) -> "$k=$v" }.toTypedArray())
            .putExtra(EXTRA_SHELL, request.shell)
            .apply { request.fontSizeSp?.let { putExtra(EXTRA_FONT_SIZE, it) } }

    fun parse(intent: Intent): ConsoleRequest = ConsoleRequest(
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Console",
        workingDirectory = intent.getStringExtra(EXTRA_CWD) ?: "/",
        executable = intent.getStringExtra(EXTRA_EXECUTABLE),
        arguments = intent.getStringArrayExtra(EXTRA_ARGS)?.toList().orEmpty(),
        environment = intent.getStringArrayExtra(EXTRA_ENV).orEmpty()
            .mapNotNull { entry -> entry.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap(),
        shell = intent.getStringExtra(EXTRA_SHELL) ?: ConsoleRequest.DEFAULT_SHELL,
        fontSizeSp = intent.getIntExtra(EXTRA_FONT_SIZE, 0).takeIf { it > 0 },
    )
}
