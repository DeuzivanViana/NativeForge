package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.model.BuildStep
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.process.ProcessSpec
import io.nativeforge.core.project.model.BuildSystemKind
import java.io.File

/**
 * Runs the project's own Makefile with GNU Make, in the project folder. The compiler,
 * flags and package paths arrive through the usual variables (see [ExternalBuildEnvironment]).
 */
class MakeBuildSystem : BuildSystem {

    override val kind: BuildSystemKind = BuildSystemKind.MAKE

    override fun plan(request: BuildRequest): BuildPlan {
        val project = request.project
        val toolchain = request.toolchain
        val config = project.config
        require(MAKEFILES.any { File(project.root, it).isFile }) { "No Makefile found in ${project.root}" }
        require(toolchain.make.exists()) { "make is not installed. Reinstall the toolchain from Settings." }

        val command = buildList {
            addAll(listOf(toolchain.make.path, "-C", project.root.path, "-j${toolchain.parallelJobs}"))
            // Termux's make defaults to Termux's own shell path, which does not exist here.
            add("SHELL=${toolchain.shell.path}")
            addAll(config.buildArguments)
            if (config.buildTarget.isNotBlank()) add(config.buildTarget)
        }
        val description = "Running make" + config.buildTarget.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return BuildPlan(
            steps = listOf(BuildStep(description, ProcessSpec(command, project.root, ExternalBuildEnvironment.create(request)))),
            artifact = ExternalArtifacts.declared(request),
            artifactSearchRoot = project.root,
        )
    }

    companion object {
        /** File names GNU Make looks for, in its own order. */
        val MAKEFILES = listOf("GNUmakefile", "makefile", "Makefile")
    }
}

/** Runs the project's own build.ninja with Ninja, in the project folder. */
class NinjaBuildSystem : BuildSystem {

    override val kind: BuildSystemKind = BuildSystemKind.NINJA

    override fun plan(request: BuildRequest): BuildPlan {
        val project = request.project
        val toolchain = request.toolchain
        val config = project.config
        require(File(project.root, "build.ninja").isFile) { "build.ninja not found in ${project.root}" }

        val command = buildList {
            addAll(listOf(toolchain.ninja.path, "-C", project.root.path, "-j", toolchain.parallelJobs.toString()))
            addAll(config.buildArguments)
            if (config.buildTarget.isNotBlank()) add(config.buildTarget)
        }
        val description = "Running ninja" + config.buildTarget.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        return BuildPlan(
            steps = listOf(BuildStep(description, ProcessSpec(command, project.root, ExternalBuildEnvironment.create(request)))),
            artifact = ExternalArtifacts.declared(request),
            artifactSearchRoot = project.root,
        )
    }
}

/** Where a project with its own build script puts its program. */
internal object ExternalArtifacts {

    /** The configured program file, or the conventional name for the project's runtime. */
    fun declared(request: BuildRequest): BuildArtifact {
        val project = request.project
        val kind = CompilerFlags.artifactKind(request)
        val file = project.config.artifactPath?.takeIf { it.isNotBlank() }?.let { path ->
            requireNotNull(SafeFiles.resolveInside(project.root, path)) { "The program file must be inside the project: $path" }
        }
            ?: CompilerFlags.artifactFile(request, project.root)
        return BuildArtifact(file, kind, CompilerFlags.runtimeLibraries(request))
    }
}
