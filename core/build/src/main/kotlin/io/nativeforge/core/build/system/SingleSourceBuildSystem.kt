package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.model.BuildStep
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.process.ProcessSpec
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.SourceFiles
import java.io.File

/**
 * Built-in build system for projects without a build script: every source under
 * `src/` is compiled to an object file and linked into the program.
 *
 * Builds are incremental. Each object is rebuilt only when its source, one of
 * the headers recorded in its clang depfile (`-MMD`), or the compile flags changed.
 * Sources are compiled on several cores at once; only the link waits for all of them.
 */
class SingleSourceBuildSystem(
    private val upToDate: IncrementalChecker = IncrementalChecker(),
) : BuildSystem {

    override val kind: BuildSystemKind = BuildSystemKind.SINGLE

    override fun plan(request: BuildRequest): BuildPlan {
        val project = request.project
        val toolchain = request.toolchain
        val outputDir = project.buildDirectory(request.buildType)
        val objectDir = File(outputDir, "obj")
        require(SafeFiles.resolveInside(project.root, project.config.sourceDir.ifBlank { "." }) != null) {
            "The source folder must be inside the project: ${project.config.sourceDir}"
        }
        val sources = SourceFiles.collectSources(project.sourceDirectory)
        require(sources.isNotEmpty()) { "No C/C++ source files found in ${project.sourceDirectory}" }

        val env = toolchain.environment()
        val commonFlags = CompilerFlags.compile(request)
        val usesCpp = project.config.language == Language.CPP || sources.any(SourceFiles::isCppSource)

        val steps = mutableListOf<BuildStep>()
        val objects = sources.map { source ->
            val relative = source.relativeTo(project.sourceDirectory).path
            val objectFile = File(objectDir, "$relative.o")
            val depFile = File(objectDir, "$relative.d")
            val isCpp = SourceFiles.isCppSource(source)
            val compiler = if (isCpp) toolchain.cxxCompiler else toolchain.cCompiler
            val command = buildList {
                add(compiler.path)
                addAll(commonFlags)
                languageStandard(request, isCpp)?.let { add("-std=$it") }
                addAll(listOf("-MMD", "-MF", depFile.path, "-c", source.path, "-o", objectFile.path))
            }
            if (!upToDate.isObjectUpToDate(source, objectFile, depFile, command)) {
                steps += BuildStep(
                    description = "Compiling $relative",
                    process = ProcessSpec(command, project.root, env),
                    parallel = true, // objects only depend on their own source
                )
                upToDate.recordPending(objectFile, command)
            }
            objectFile
        }

        val artifactFile = CompilerFlags.artifactFile(request, outputDir)
        val linkCommand = buildList {
            add((if (usesCpp) toolchain.cxxCompiler else toolchain.cCompiler).path)
            addAll(objects.map { it.path })
            add("-o")
            add(artifactFile.path)
            addAll(CompilerFlags.link(request))
        }
        if (steps.isNotEmpty() || !upToDate.isLinkUpToDate(artifactFile, objects, linkCommand)) {
            steps += BuildStep("Linking ${artifactFile.name}", ProcessSpec(linkCommand, project.root, env))
            upToDate.recordPending(artifactFile, linkCommand)
        }

        return BuildPlan(
            steps = steps,
            artifact = BuildArtifact(artifactFile, CompilerFlags.artifactKind(request), CompilerFlags.runtimeLibraries(request)),
            // At most 4: each clang can take several hundred MB on a phone.
            parallelJobs = toolchain.parallelJobs.coerceAtMost(4),
        )
    }

    private fun languageStandard(request: BuildRequest, isCpp: Boolean): String? {
        val configured = request.project.config.standard
        val configuredIsCpp = configured.startsWith("c++") || configured.startsWith("gnu++")
        return when {
            isCpp == configuredIsCpp -> configured
            isCpp -> Language.CPP.defaultStandard
            else -> Language.C.defaultStandard
        }
    }
}

/**
 * Decides whether outputs must be rebuilt. The command used to produce each
 * output is stored next to it (`<output>.cmd`) so flag changes trigger rebuilds.
 */
class IncrementalChecker {

    fun isObjectUpToDate(source: File, objectFile: File, depFile: File, command: List<String>): Boolean {
        if (!objectFile.isFile || !depFile.isFile || !commandMatches(objectFile, command)) return false
        val outputTime = objectFile.lastModified()
        val inputs = parseDepFile(depFile.readText()).map(::File) + source
        return inputs.all { it.isFile && it.lastModified() <= outputTime }
    }

    fun isLinkUpToDate(artifact: File, objects: List<File>, command: List<String>): Boolean {
        if (!artifact.isFile || !commandMatches(artifact, command)) return false
        return objects.all { it.isFile && it.lastModified() <= artifact.lastModified() }
    }

    /** Stores [command] so the next build can compare it. Written before the step runs. */
    fun recordPending(output: File, command: List<String>) {
        output.parentFile?.mkdirs()
        // A missing or stale output is detected by timestamp, so writing early is safe.
        commandFile(output).writeText(command.joinToString("\n"))
    }

    private fun commandMatches(output: File, command: List<String>): Boolean {
        val file = commandFile(output)
        return file.isFile && file.readText() == command.joinToString("\n")
    }

    private fun commandFile(output: File) = File(output.path + ".cmd")

    companion object {
        /** Parses a Makefile-style depfile: `out.o: a.c b.h \` with escaped spaces. */
        fun parseDepFile(text: String): List<String> {
            val body = text.replace("\\\n", " ").replace("\\\r\n", " ").substringAfter(": ", "")
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '\\' && i + 1 < body.length && body[i + 1] == ' ' -> { current.append(' '); i++ }
                    c.isWhitespace() -> if (current.isNotEmpty()) { result += current.toString(); current.clear() }
                    else -> current.append(c)
                }
                i++
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }
    }
}
