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

/**
 * [ProcessRunner] backed by [ProcessBuilder]; works on both the JVM and Android.
 *
 * [priority] is the Java thread priority processes start from. On Android a thread's
 * priority is its nice value and a forked process inherits it, so a lower priority makes
 * the process (and everything it starts) yield the CPU to the app; the app lowers it for
 * builds when the phone is hot. Desktop JVMs ignore it.
 */
class JvmProcessRunner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val priority: () -> Int = { Thread.NORM_PRIORITY },
) : ProcessRunner {

    override fun run(spec: ProcessSpec): Flow<ProcessEvent> = channelFlow {
        val builder = ProcessBuilder(spec.command).directory(spec.workingDirectory)
        builder.environment().apply {
            if (spec.clearEnvironment) clear()
            putAll(spec.environment)
        }
        val process = start(builder, priority().coerceIn(Thread.MIN_PRIORITY, Thread.MAX_PRIORITY))
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

    /** Starts the process from a thread of [priority] (a short-lived one, so pool threads keep theirs). */
    private fun start(builder: ProcessBuilder, priority: Int): Process {
        if (priority == Thread.currentThread().priority) return builder.start()
        var process: Process? = null
        var error: Throwable? = null
        val starter = Thread({ try { process = builder.start() } catch (t: Throwable) { error = t } }, "nf-process-start")
        starter.priority = priority
        starter.start()
        starter.join()
        error?.let { throw it }
        return process!!
    }

    private suspend fun pump(stream: InputStream, emit: suspend (String) -> Unit) {
        val reader = stream.bufferedReader()
        while (true) {
            val line = runInterruptible { reader.readLine() } ?: break
            emit(line)
        }
    }
}
