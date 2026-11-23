package io.nativeforge.core.toolchain

import java.io.File

/**
 * Compiler and linker inputs contributed by an installed library package.
 * Produced by core:packages and consumed by the build systems, which keeps
 * those two modules independent of each other.
 */
data class NativeDependency(
    val name: String,
    val includeDirectories: List<File> = emptyList(),
    val libraryDirectories: List<File> = emptyList(),
    /** Libraries linked with `-l<name>`. */
    val linkLibraries: List<String> = emptyList(),
    val compileFlags: List<String> = emptyList(),
    val linkFlags: List<String> = emptyList(),
    /** Install prefixes added to CMAKE_PREFIX_PATH so find_package() works. */
    val cmakePrefixes: List<File> = emptyList(),
    /** Shared libraries that must be loaded (or bundled) before the program, in order. */
    val runtimeLibraries: List<File> = emptyList(),
)
