package io.nativeforge.core.toolchain

import java.io.File

/** A C/C++ toolchain capable of building programs on the device. */
interface Toolchain {
    val abi: Abi

    /** Minimum Android API level programs are compiled for. */
    val apiLevel: Int

    val cCompiler: File
    val cxxCompiler: File
    val cmake: File
    val ninja: File
    val make: File

    /** clangd language server (part of Termux's clang package), for completion and diagnostics. */
    val clangd: File get() = File(cCompiler.parentFile, "clangd")

    /** clang-format, used by the editor's "Format document". */
    val clangFormat: File get() = File(cCompiler.parentFile, "clang-format")

    /** POSIX shell used to run Makefile recipes. */
    val shell: File

    /** Number of parallel jobs for make and ninja: cores, limited by free memory (see [BuildParallelism]). */
    val parallelJobs: Int get() = BuildParallelism.jobs()

    /** ccache, when installed. Compile commands then run through it (see the build systems). */
    val compilerCache: File? get() = null

    /** True when objects are ELF and `-gsplit-dwarf` works: debug info stays out of the link. */
    val supportsSplitDwarf: Boolean get() = false

    /** True when the linker is lld, which does ThinLTO without a plugin. */
    val supportsThinLto: Boolean get() = false

    /**
     * Prepares the toolchain before a build (for example writing compiler config files),
     * so that tools invoked by third-party build scripts find the sysroot on their own.
     */
    fun prepare() {}

    /** Environment every toolchain process must run with. */
    fun environment(): Map<String, String>

    /** Flags added to every compile command (target, sysroot...). */
    fun baseCompileFlags(): List<String>

    /** Flags added to every link command. */
    fun baseLinkFlags(): List<String>

    /** Shared runtime libraries a program built with this toolchain needs at run time. */
    fun runtimeLibraries(): List<File>

    /** Returns the names of required tools that are missing, empty when ready. */
    fun missingComponents(): List<String>
}
