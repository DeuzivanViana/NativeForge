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
    }
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
