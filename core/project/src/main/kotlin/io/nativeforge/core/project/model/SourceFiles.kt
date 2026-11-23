package io.nativeforge.core.project.model

import java.io.File

/** File classification helpers shared by the editor and the build systems. */
object SourceFiles {
    private val C_SOURCES = setOf("c")
    private val CPP_SOURCES = setOf("cpp", "cc", "cxx", "c++")
    private val HEADERS = setOf("h", "hpp", "hh", "hxx", "inl")
    private val ASSEMBLY = setOf("s", "S")

    fun isCSource(file: File) = file.extension in C_SOURCES
    fun isCppSource(file: File) = file.extension.lowercase() in CPP_SOURCES
    fun isHeader(file: File) = file.extension.lowercase() in HEADERS
    fun isAssembly(file: File) = file.extension in ASSEMBLY
    fun isCompilable(file: File) = isCSource(file) || isCppSource(file) || isAssembly(file)

    /**
     * Collects compilable sources below [directory], sorted for reproducible builds.
     * Hidden folders, build output folders and symbolic links are skipped.
     */
    fun collectSources(directory: File): List<File> =
        directory.walkTopDown()
            .onEnter { it == directory || (!it.name.startsWith(".") && it.name !in IGNORED_DIRS && !isSymlink(it)) }
            .filter { it.isFile && isCompilable(it) && !isSymlink(it) }
            .sortedBy { it.path }
            .toList()

    /** Folders that hold generated files rather than sources. */
    val IGNORED_DIRS = setOf("build", "CMakeFiles", "node_modules", "out")

    fun isSymlink(file: File): Boolean = java.nio.file.Files.isSymbolicLink(file.toPath())
}
