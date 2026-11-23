package io.nativeforge.core.common.process

import kotlinx.coroutines.flow.Flow
import java.io.File

/** Description of a process to launch. */
data class ProcessSpec(
    val command: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String> = emptyMap(),
    /** When true the inherited environment is cleared before [environment] is applied. */
    val clearEnvironment: Boolean = false,
) {
    init {
        require(command.isNotEmpty()) { "command must not be empty" }
    }

    /** Human-readable command line, used for build logs. */
    fun commandLine(): String = command.joinToString(" ") { arg ->
        if (arg.any { it.isWhitespace() || it == '"' }) "\"" + arg.replace("\"", "\\\"") + "\"" else arg
    }
}

enum class OutputStream { STDOUT, STDERR }

sealed interface ProcessEvent {
    data class Output(val stream: OutputStream, val line: String) : ProcessEvent
    data class Exited(val exitCode: Int) : ProcessEvent
}

/**
 * Launches processes and exposes their output as a cold [Flow]. Collecting the
 * flow starts the process; cancelling the collector destroys it.
 */
fun interface ProcessRunner {
    fun run(spec: ProcessSpec): Flow<ProcessEvent>
}
