package io.nativeforge.core.build.system

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.project.model.BuildType

/**
 * Environment for build scripts NativeForge does not generate (Makefiles, build.ninja).
 * It follows the conventions those scripts read: CC/CXX, CFLAGS/CXXFLAGS, LDFLAGS and
 * LDLIBS (GNU Make's built-in link rule appends LDLIBS after the objects).
 */
internal object ExternalBuildEnvironment {

    fun create(request: BuildRequest): Map<String, String> {
        val toolchain = request.toolchain
        val config = request.project.config
        val compile = CompilerFlags.compile(request).joinToString(" ")
        // gcc/g++/cc/c++ wrappers come first on PATH: Makefiles that hard-code a compiler
        // name or overwrite CFLAGS/LDFLAGS still build an Android program (see CompilerWrappers).
        val wrappers = CompilerWrappers.write(request)
        val base = toolchain.environment()
        val path = listOfNotNull(wrappers.path, base["PATH"]).joinToString(":")
        return base + mapOf(
            "PATH" to path,
            // The target and sysroot are inside the wrappers, so they survive Makefiles that
            // overwrite CFLAGS.
            "CC" to java.io.File(wrappers, "cc").path,
            "CXX" to java.io.File(wrappers, "c++").path,
            "CFLAGS" to compile,
            "CXXFLAGS" to compile,
            "LDFLAGS" to CompilerFlags.linkOptions(request).joinToString(" "),
            "LDLIBS" to CompilerFlags.linkLibraries(request).joinToString(" "),
            "SHELL" to toolchain.shell.path,
            "NATIVEFORGE" to "1",
            "NATIVEFORGE_RUNTIME" to config.runtime.name,
            "NATIVEFORGE_BUILD_TYPE" to if (request.buildType == BuildType.DEBUG) "debug" else "release",
            "NATIVEFORGE_INCLUDE_DIRS" to request.dependencies.flatMap { it.includeDirectories }.joinToString(":") { it.path },
            "NATIVEFORGE_LIB_DIRS" to request.dependencies.flatMap { it.libraryDirectories }.joinToString(":") { it.path },
        )
    }
}
