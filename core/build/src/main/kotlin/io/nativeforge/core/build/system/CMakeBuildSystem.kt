package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.ArtifactKind
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.model.BuildStep
import io.nativeforge.core.common.SafeFiles
import io.nativeforge.core.common.process.ProcessSpec
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.BuildType
import io.nativeforge.core.project.model.CMakeGenerator
import java.io.File

/**
 * Configures the project's CMakeLists.txt with the generator chosen in the project
 * (Ninja or Unix Makefiles), then builds the configured target.
 *
 * Installed packages work without editing the CMakeLists.txt: their headers are on the
 * include path and their libraries are appended to every link through
 * CMAKE_<LANG>_STANDARD_LIBRARIES. CMAKE_PREFIX_PATH also lets `find_package()` find them,
 * and NATIVEFORGE_LIBRARIES lists them for `target_link_libraries()`.
 */
class CMakeBuildSystem : BuildSystem {

    override val kind: BuildSystemKind = BuildSystemKind.CMAKE

    override fun plan(request: BuildRequest): BuildPlan {
        val project = request.project
        val toolchain = request.toolchain
        val config = project.config
        require(File(project.root, "CMakeLists.txt").isFile) { "CMakeLists.txt not found in ${project.root}" }
        val generator = config.cmakeGenerator
        if (generator == CMakeGenerator.MAKEFILES) {
            require(toolchain.make.exists()) { "make is not installed. Reinstall the toolchain from Settings." }
        }

        // One build folder per generator: CMake refuses to switch generators in place.
        val buildDir = project.buildDirectory(request.buildType).let {
            if (generator == CMakeGenerator.NINJA) it else File(it.parentFile, "${it.name}-makefiles")
        }
        val env = toolchain.environment()

        val compileFlags = (
            CompilerFlags.compile(request, optimize = false) +
                if (request.buildType == BuildType.RELEASE) CompilerFlags.RELEASE_SECTION_FLAGS else emptyList()
            ).joinToString(" ")
        val linkOptions = CompilerFlags.linkOptions(request)
            .filterNot { it == "-shared" || it in CompilerFlags.SHARED_LIBRARY_GUARDS }
            .joinToString(" ")
        val libraries = CompilerFlags.linkLibraries(request).joinToString(" ")
        val prefixPath = request.dependencies.flatMap { it.cmakePrefixes }.joinToString(";") { it.path }

        val definitions = linkedMapOf(
            "CMAKE_BUILD_TYPE" to if (request.buildType == BuildType.DEBUG) "Debug" else "Release",
            "CMAKE_MAKE_PROGRAM" to (if (generator == CMakeGenerator.NINJA) toolchain.ninja else toolchain.make).path,
            "CMAKE_C_COMPILER" to toolchain.cCompiler.path,
            "CMAKE_CXX_COMPILER" to toolchain.cxxCompiler.path,
            "CMAKE_C_FLAGS" to compileFlags,
            "CMAKE_CXX_FLAGS" to compileFlags,
            "CMAKE_EXE_LINKER_FLAGS" to linkOptions,
            "CMAKE_SHARED_LINKER_FLAGS" to (listOf(linkOptions) + CompilerFlags.SHARED_LIBRARY_GUARDS).joinToString(" "),
            "CMAKE_MODULE_LINKER_FLAGS" to linkOptions,
            "CMAKE_C_STANDARD_LIBRARIES" to libraries,
            "CMAKE_CXX_STANDARD_LIBRARIES" to libraries,
            "CMAKE_EXPORT_COMPILE_COMMANDS" to "ON",
            "NATIVEFORGE" to "ON",
            "NATIVEFORGE_RUNTIME" to config.runtime.name,
            "NATIVEFORGE_LIBRARIES" to request.dependencies.asReversed().flatMap { it.linkLibraries }.joinToString(";"),
            "NATIVEFORGE_INCLUDE_DIRS" to request.dependencies.flatMap { it.includeDirectories }.joinToString(";") { it.path },
        )
        if (prefixPath.isNotEmpty()) definitions["CMAKE_PREFIX_PATH"] = prefixPath
        CompilerCache.launcher(request)?.let { ccache ->
            definitions["CMAKE_C_COMPILER_LAUNCHER"] = ccache.path
            definitions["CMAKE_CXX_COMPILER_LAUNCHER"] = ccache.path
        }
        definitions.putAll(config.cmakeOptions)

        val configure = buildList {
            add(toolchain.cmake.path)
            addAll(listOf("-G", generator.cmakeName, "-S", project.root.path, "-B", buildDir.path))
            // NativeForge always passes its own variables (NATIVEFORGE_*, the standard libraries);
            // a project that does not read them is fine, so don't warn about them on every configure.
            add("--no-warn-unused-cli")
            definitions.forEach { (key, value) -> add("-D$key=$value") }
        }
        val build = buildList {
            addAll(listOf(toolchain.cmake.path, "--build", buildDir.path, "--parallel", toolchain.parallelJobs.toString()))
            if (config.cmakeTarget.isNotBlank()) addAll(listOf("--target", config.cmakeTarget))
            if (generator == CMakeGenerator.MAKEFILES || config.buildArguments.isNotEmpty()) add("--")
            // Termux's make defaults to Termux's own shell path, which does not exist here.
            if (generator == CMakeGenerator.MAKEFILES) add("SHELL=${toolchain.shell.path}")
            addAll(config.buildArguments)
        }

        val kind = CompilerFlags.artifactKind(request)
        val artifact = config.artifactPath?.takeIf { it.isNotBlank() }?.let { path ->
            requireNotNull(SafeFiles.resolveInside(project.root, path)) { "The program file must be inside the project: $path" }
        }
            ?: when (kind) {
                ArtifactKind.SHARED_LIBRARY -> File(buildDir, "lib${config.cmakeTarget}.so")
                ArtifactKind.EXECUTABLE -> File(buildDir, config.cmakeTarget)
            }
        val targetName = config.cmakeTarget.ifBlank { "all" }
        return BuildPlan(
            steps = listOf(
                BuildStep("Configuring with CMake (${generator.cmakeName})", ProcessSpec(configure, project.root, env)),
                BuildStep("Building target '$targetName'", ProcessSpec(build, project.root, env)),
            ),
            artifact = BuildArtifact(artifact, kind, CompilerFlags.runtimeLibraries(request)),
            artifactSearchRoot = buildDir,
        )
    }
}
