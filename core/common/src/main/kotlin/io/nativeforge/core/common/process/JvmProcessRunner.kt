package io.nativeforge.core.common.process

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.InputStream

/** [ProcessRunner] backed by [ProcessBuilder]; works on both the JVM and Android. */
class JvmProcessRunner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProcessRunner {

    override fun run(spec: ProcessSpec): Flow<ProcessEvent> = channelFlow {
        val builder = ProcessBuilder(spec.command).directory(spec.workingDirectory)
        builder.environment().apply {
            if (spec.clearEnvironment) clear()
            putAll(spec.environment)
        }
        val process = builder.start()
        process.outputStream.close()

        val stdout = launch { pump(process.inputStream) { send(ProcessEvent.Output(OutputStream.STDOUT, it)) } }
        val stderr = launch { pump(process.errorStream) { send(ProcessEvent.Output(OutputStream.STDERR, it)) } }

        launch {
            val exitCode = runInterruptible { process.waitFor() }
            stdout.join()
            stderr.join()
            send(ProcessEvent.Exited(exitCode))
            channel.close()
        }

        awaitClose {
            if (process.isAlive) process.destroyForcibly()
        }
    }.flowOn(dispatcher)

    private suspend fun pump(stream: InputStream, emit: suspend (String) -> Unit) {
        val reader = stream.bufferedReader()
        while (true) {
            val line = runInterruptible { reader.readLine() } ?: break
            emit(line)
        }
    }
}
