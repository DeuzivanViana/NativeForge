package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.ArtifactKind
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.project.model.BuildType
import io.nativeforge.core.project.model.RuntimeKind
import java.io.File

/** Flag computation shared by every build system so they behave consistently. */
internal object CompilerFlags {

    fun artifactKind(request: BuildRequest): ArtifactKind =
        if (request.project.config.runtime.producesSharedLibrary) ArtifactKind.SHARED_LIBRARY else ArtifactKind.EXECUTABLE

    fun optimization(buildType: BuildType): List<String> = when (buildType) {
        BuildType.DEBUG -> listOf("-O0", "-g")
        BuildType.RELEASE -> listOf("-O2", "-DNDEBUG")
    }

    /**
     * Flags common to C and C++ compilation, excluding the language standard.
     * [optimize] is false for CMake, which adds its own flags for the build type.
     */
    fun compile(request: BuildRequest, optimize: Boolean = true): List<String> = buildList {
        val project = request.project
        addAll(request.toolchain.baseCompileFlags())
        if (optimize) addAll(optimization(request.buildType))
        if (artifactKind(request) == ArtifactKind.SHARED_LIBRARY) add("-fPIC")
        add("-DNATIVEFORGE=1")
        add("-DNATIVEFORGE_RUNTIME_${project.config.runtime.name}=1")
        add("-I${project.sourceDirectory.path}")
        File(project.root, "include").takeIf { it.isDirectory }?.let { add("-I${it.path}") }
        request.dependencies.forEach { dep ->
            // -isystem: warnings inside library headers (raygui, glm...) are not the user's problem.
            dep.includeDirectories.forEach { add("-isystem"); add(it.path) }
            addAll(dep.compileFlags)
        }
        addAll(project.config.compilerFlags)
    }

    fun link(request: BuildRequest): List<String> = linkOptions(request) + linkLibraries(request)

    /** Linker options that may appear anywhere on the command line (before the objects). */
    fun linkOptions(request: BuildRequest): List<String> = buildList {
        addAll(request.toolchain.baseLinkFlags())
        if (artifactKind(request) == ArtifactKind.SHARED_LIBRARY) {
            add("-shared")
            addAll(SHARED_LIBRARY_GUARDS)
        }
        if (request.project.config.runtime == RuntimeKind.NATIVE_ACTIVITY) {
            // Keep the entry point exported even when it comes from a static library.
            add("-Wl,-u,ANativeActivity_onCreate")
        }
        request.dependencies.asReversed().forEach { dep ->
            dep.libraryDirectories.forEach {
                add("-L${it.path}")
                add("-Wl,-rpath,${it.path}")
            }
        }
    }

    /** Libraries, which must come after the objects that use them. */
    fun linkLibraries(request: BuildRequest): List<String> = buildList {
        // Dependencies arrive dependencies-first; static archives must be linked dependents-first.
        request.dependencies.asReversed().forEach { dep ->
            dep.linkLibraries.forEach { add("-l$it") }
            addAll(dep.linkFlags)
        }
        addAll(request.project.config.linkerFlags)
        // libm is separate from libc on Android; C code calling sinf() etc. needs it.
        add("-lm")
    }

    /**
     * A shared library may link with undefined symbols, which then only fail when
     * the program is opened ("cannot locate symbol"), so the window just closes.
     * Report them at link time instead, as the NDK's own build systems do.
     */
    val SHARED_LIBRARY_GUARDS = listOf("-Wl,--no-undefined")

    fun runtimeLibraries(request: BuildRequest): List<File> =
        (request.toolchain.runtimeLibraries().filter { it.exists() } +
            request.dependencies.flatMap { it.runtimeLibraries }).distinct()

    fun artifactFile(request: BuildRequest, outputDir: File): File = when (artifactKind(request)) {
        ArtifactKind.SHARED_LIBRARY -> File(outputDir, "libmain.so")
        ArtifactKind.EXECUTABLE -> File(outputDir, executableName(request.project.name))
    }

    fun executableName(projectName: String): String =
        projectName.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "program" }
}
