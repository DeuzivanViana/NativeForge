package io.nativeforge.core.build

import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.build.diagnostics.DiagnosticParser
import io.nativeforge.core.build.diagnostics.Severity
import io.nativeforge.core.build.diagnostics.ToolchainDiagnosticParser
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildResult
import io.nativeforge.core.build.model.BuildStep
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.common.process.ProcessEvent
import io.nativeforge.core.common.process.ProcessRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the steps of a [BuildPlan] in order, stopping at the first failure.
 *
 * Neighbouring [BuildStep.parallel] steps run at the same time, at most [BuildPlan.parallelJobs]
 * of them. Their output is held until each one finishes, so the lines of one compile (an error
 * and its notes) stay together in the log. After a failure no new step starts; the ones
 * already running finish and report their errors too.
 */
class BuildExecutor(
    private val processRunner: ProcessRunner,
    private val diagnosticParser: DiagnosticParser = ToolchainDiagnosticParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun execute(plan: BuildPlan): Flow<BuildEvent> = channelFlow {
        val start = clock()
        val diagnostics = mutableListOf<Diagnostic>()
        val total = plan.steps.size
        val reporting = Mutex() // one step's events at a time; also guards diagnostics

        // Callers hold [reporting].
        suspend fun sendLines(lines: List<ProcessEvent.Output>) {
            for (line in lines) {
                send(BuildEvent.Output(line.stream, line.line))
                diagnosticParser.parse(line.line)?.let {
                    diagnostics += it
                    send(BuildEvent.DiagnosticReported(it))
                }
            }
        }

        /** Runs one step and returns its exit code. Streams its output unless [buffered]. */
        suspend fun run(index: Int, step: BuildStep, buffered: Boolean): Int {
            step.process.workingDirectory.mkdirs()
            if (!buffered) reporting.withLock { send(BuildEvent.StepStarted(index, total, step)) }
            val held = mutableListOf<ProcessEvent.Output>()
            var exitCode = -1
            processRunner.run(step.process).collect { event ->
                when (event) {
                    is ProcessEvent.Output -> if (buffered) held += event else reporting.withLock { sendLines(listOf(event)) }
                    is ProcessEvent.Exited -> exitCode = event.exitCode
                }
            }
            reporting.withLock {
                if (buffered) send(BuildEvent.StepStarted(index, total, step))
                sendLines(held)
                send(BuildEvent.StepFinished(step, exitCode))
            }
            return exitCode
        }

        var success = true
        var index = 0
        while (index < total && success) {
            val step = plan.steps[index]
            // A run of parallel steps, or a single ordinary step.
            var end = index + 1
            if (step.parallel) while (end < total && plan.steps[end].parallel) end++
            if (end - index == 1 || plan.parallelJobs <= 1) {
                for (i in index until end) {
                    if (run(i, plan.steps[i], buffered = false) != 0) {
                        success = false
                        break
                    }
                }
            } else {
                val permits = Semaphore(plan.parallelJobs)
                val failed = AtomicBoolean(false)
                coroutineScope {
                    (index until end).map { i ->
                        async {
                            permits.withPermit {
                                if (!failed.get() && run(i, plan.steps[i], buffered = true) != 0) failed.set(true)
                            }
                        }
                    }.awaitAll()
                }
                success = !failed.get()
            }
            index = end
        }

        success = success && diagnostics.none { it.severity == Severity.ERROR }
        var artifact = plan.artifact
        if (success && !artifact.file.exists()) {
            val found = plan.artifactSearchRoot?.let { ArtifactLocator.newest(it, artifact.kind, start) }
            if (found != null) {
                artifact = artifact.copy(file = found)
                send(BuildEvent.Output(OutputStream.STDOUT, "Program: ${found.path}"))
            } else {
                success = false
                send(
                    BuildEvent.Output(
                        OutputStream.STDERR,
                        "The build finished but ${artifact.file.path} was not found. " +
                            "Set the program file in Build settings.",
                    ),
                )
            }
        }
        send(
            BuildEvent.Finished(
                BuildResult(
                    success = success,
                    artifact = artifact.takeIf { success },
                    diagnostics = diagnostics,
                    durationMillis = clock() - start,
                ),
            ),
        )
    }
}
