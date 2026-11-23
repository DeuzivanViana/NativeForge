package io.nativeforge.core.build

import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.build.diagnostics.DiagnosticParser
import io.nativeforge.core.build.diagnostics.Severity
import io.nativeforge.core.build.diagnostics.ToolchainDiagnosticParser
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildResult
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.common.process.ProcessEvent
import io.nativeforge.core.common.process.ProcessRunner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Runs the steps of a [BuildPlan] sequentially, stopping at the first failure. */
class BuildExecutor(
    private val processRunner: ProcessRunner,
    private val diagnosticParser: DiagnosticParser = ToolchainDiagnosticParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun execute(plan: BuildPlan): Flow<BuildEvent> = flow {
        val start = clock()
        val diagnostics = mutableListOf<Diagnostic>()
        var success = true

        for ((index, step) in plan.steps.withIndex()) {
            emit(BuildEvent.StepStarted(index, plan.steps.size, step))
            step.process.workingDirectory.mkdirs()
            var exitCode = -1
            processRunner.run(step.process).collect { event ->
                when (event) {
                    is ProcessEvent.Output -> {
                        emit(BuildEvent.Output(event.stream, event.line))
                        diagnosticParser.parse(event.line)?.let {
                            diagnostics += it
                            emit(BuildEvent.DiagnosticReported(it))
                        }
                    }
                    is ProcessEvent.Exited -> exitCode = event.exitCode
                }
            }
            emit(BuildEvent.StepFinished(step, exitCode))
            if (exitCode != 0) {
                success = false
                break
            }
        }

        success = success && diagnostics.none { it.severity == Severity.ERROR }
        var artifact = plan.artifact
        if (success && !artifact.file.exists()) {
            val found = plan.artifactSearchRoot?.let { ArtifactLocator.newest(it, artifact.kind, start) }
            if (found != null) {
                artifact = artifact.copy(file = found)
                emit(BuildEvent.Output(OutputStream.STDOUT, "Program: ${found.path}"))
            } else {
                success = false
                emit(
                    BuildEvent.Output(
                        OutputStream.STDERR,
                        "The build finished but ${artifact.file.path} was not found. " +
                            "Set the program file in Build settings.",
                    ),
                )
            }
        }
        emit(
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
