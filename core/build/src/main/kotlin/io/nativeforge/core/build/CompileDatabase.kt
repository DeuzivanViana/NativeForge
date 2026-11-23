package io.nativeforge.core.build

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.system.CompilerFlags
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.SourceFiles
import java.io.File

/** One entry of a `compile_commands.json` file. */
data class CompileCommand(val directory: File, val file: File, val arguments: List<String>)

/**
 * Compilation database for editor tools (clangd). Each source gets the flags the
 * built-in build would use, so completion sees the same headers and macros as the build.
 *
 * CMake projects have a more exact database written by CMake itself
 * ([cmakeDatabase]); it is preferred when it exists.
 */
object CompileDatabase {

    fun commands(request: BuildRequest): List<CompileCommand> {
        val project = request.project
        val toolchain = request.toolchain
        val root = if (project.config.buildSystem == BuildSystemKind.SINGLE) project.sourceDirectory else project.root
        val sources = if (root.isDirectory) SourceFiles.collectSources(root) else emptyList()
        val flags = CompilerFlags.compile(request) + includeFolders(request)
        return sources.filterNot(SourceFiles::isAssembly).map { source ->
            val isCpp = SourceFiles.isCppSource(source)
            val arguments = buildList {
                add((if (isCpp) toolchain.cxxCompiler else toolchain.cCompiler).path)
                addAll(flags)
                add("-std=" + standard(request, isCpp))
                addAll(listOf("-c", source.path))
            }
            CompileCommand(project.root, source, arguments)
        }
    }

    /** The database CMake wrote during the last configure, or null. */
    fun cmakeDatabase(request: BuildRequest): File? =
        request.project.buildDirectory.listFiles().orEmpty()
            .map { File(it, "compile_commands.json") }
            .filter { it.isFile }
            .maxByOrNull { it.lastModified() }

    /** Serializes [commands] as a compile_commands.json document. */
    fun toJson(commands: List<CompileCommand>): String = buildString {
        append("[\n")
        commands.forEachIndexed { index, command ->
            append("  {\"directory\": ").append(jsonString(command.directory.path))
            append(", \"file\": ").append(jsonString(command.file.path))
            append(", \"arguments\": [")
            command.arguments.joinTo(this, ", ") { jsonString(it) }
            append("]}")
            if (index < commands.lastIndex) append(',')
            append('\n')
        }
        append("]\n")
    }

    /**
     * Folders desktop projects usually pass with -I in their Makefiles (`include/`,
     * `third-party/<lib>/include`): without them clangd would report missing headers.
     */
    private fun includeFolders(request: BuildRequest): List<String> {
        if (request.project.config.buildSystem == BuildSystemKind.SINGLE) return emptyList()
        val root = request.project.root
        return root.walkTopDown().maxDepth(4)
            .onEnter { it == root || (!it.name.startsWith(".") && it.name !in SourceFiles.IGNORED_DIRS) }
            .filter { it.isDirectory && it.name in setOf("include", "src") && it != request.project.sourceDirectory }
            .map { "-I${it.path}" }
            .toList()
    }

    private fun standard(request: BuildRequest, isCpp: Boolean): String {
        val configured = request.project.config.standard
        val configuredIsCpp = configured.startsWith("c++") || configured.startsWith("gnu++")
        return when {
            isCpp == configuredIsCpp -> configured
            isCpp -> Language.CPP.defaultStandard
            else -> Language.C.defaultStandard
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
