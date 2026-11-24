package io.nativeforge.core.build.model

import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.common.process.ProcessSpec
import io.nativeforge.core.project.model.BuildType
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.toolchain.NativeDependency
import io.nativeforge.core.toolchain.Toolchain
import java.io.File

/** Input to a build. */
data class BuildRequest(
    val project: Project,
    val toolchain: Toolchain,
    val buildType: BuildType = BuildType.DEBUG,
    val dependencies: List<NativeDependency> = emptyList(),
)

enum class ArtifactKind { EXECUTABLE, SHARED_LIBRARY }

/** The program produced by a build plus what it needs at run time. */
data class BuildArtifact(
    val file: File,
    val kind: ArtifactKind,
    /** Shared libraries to load before [file], in order. */
    val runtimeLibraries: List<File>,
)

/** One process invocation of a build. */
data class BuildStep(
    val description: String,
    val process: ProcessSpec,
    /**
     * True when the step does not depend on the parallel steps next to it (each source file's
     * compile step), so the executor may run them at the same time on several cores.
     */
    val parallel: Boolean = false,
)

/** Ordered steps that produce [artifact]. An empty step list means everything is up to date. */
data class BuildPlan(
    val steps: List<BuildStep>,
    val artifact: BuildArtifact,
    /**
     * When [artifact] does not exist after the build, the newest program of the same
     * kind written below this folder during the build is used instead (Make and Ninja
     * projects name their outputs freely).
     */
    val artifactSearchRoot: File? = null,
    /** How many [BuildStep.parallel] steps may run at the same time. */
    val parallelJobs: Int = 1,
)

data class BuildResult(
    val success: Boolean,
    val artifact: BuildArtifact?,
    val diagnostics: List<Diagnostic>,
    val durationMillis: Long,
)

/** Events streamed to the UI while a build runs. */
sealed interface BuildEvent {
    data class StepStarted(val index: Int, val total: Int, val step: BuildStep) : BuildEvent
    data class Output(val stream: OutputStream, val line: String) : BuildEvent
    data class DiagnosticReported(val diagnostic: Diagnostic) : BuildEvent
    data class StepFinished(val step: BuildStep, val exitCode: Int) : BuildEvent
    data class Finished(val result: BuildResult) : BuildEvent
}
