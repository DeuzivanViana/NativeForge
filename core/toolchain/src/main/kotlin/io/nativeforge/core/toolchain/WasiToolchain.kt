package io.nativeforge.core.toolchain

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Builds console programs as WebAssembly (`wasm32-wasip1`) with the device's clang and
 * wasm-ld, against the WASI sysroot of Termux's `wasi-libc` package.
 *
 * It runs the same binaries as [host] (environment, parallelism), so only flags and
 * readiness differ. `--no-default-config` keeps clang from reading the `clang.cfg` that
 * [ClangToolchain.prepare] writes: its Android target, `-L<prefix>/lib` (which holds
 * Android's libc.a) and `-rpath` would break a wasm link.
 */
class WasiToolchain(
    private val host: Toolchain,
    private val layout: ToolchainLayout,
) : Toolchain by host {

    val sysroot: File get() = layout.wasiSysroot
    val linker: File get() = File(layout.binDir, "wasm-ld")

    override fun prepare() {
        host.prepare()
        linkBuiltins()
    }

    override fun environment(): Map<String, String> = host.environment()

    override fun baseCompileFlags(): List<String> = targetFlags() + listOf(
        "-fdiagnostics-color=never",
        "-DNATIVEFORGE_WEB=1",
    ) + EMULATIONS.map { "-D_WASI_EMULATED_${it.uppercase().replace('-', '_')}" }

    override fun baseLinkFlags(): List<String> = targetFlags() + listOf(
        // wasm-ld's default stack is 64 KiB, which recursive programs overflow quickly.
        "-Wl,-z,stack-size=$STACK_SIZE",
    ) + EMULATIONS.map { "-lwasi-emulated-$it" }

    /** A wasm module needs nothing next to it: libc and libc++ are linked statically. */
    override fun runtimeLibraries(): List<File> = emptyList()

    override fun missingComponents(): List<String> = buildList {
        if (!cCompiler.exists()) add("clang")
        if (!linker.exists()) add("wasm-ld (lld)")
        if (!File(sysroot, "lib/$TARGET/libc.a").isFile) add("WASI sysroot ($PACKAGE)")
    }

    /**
     * What installed packages can give a wasm build: only their headers, so header-only
     * libraries (glm, nlohmann-json, stb...) keep working. Their compiled libraries are
     * Android ELF files that wasm-ld cannot link, and headers in the Termux prefix
     * (`<prefix>/include`, which also holds Android's libc headers) would shadow
     * wasi-libc's, so both are left out.
     */
    fun webDependencies(dependencies: List<NativeDependency>): List<NativeDependency> =
        dependencies.mapNotNull { dep ->
            val headers = dep.includeDirectories.filterNot { it.toPath().normalize().startsWith(layout.prefix.toPath().normalize()) }
            if (headers.isEmpty()) null else NativeDependency(dep.name, includeDirectories = headers)
        }

    private fun targetFlags() = listOf("--no-default-config", "--target=$TARGET", "--sysroot=${sysroot.path}")

    /**
     * `wasi-libc` installs compiler-rt's wasm builtins into the resource directory of the
     * clang version Termux built it with. When the installed clang is older or newer,
     * clang looks in its own `lib/clang/<major>` and the link fails, so the wasm runtime
     * folders are linked into every resource directory that lacks them.
     */
    internal fun linkBuiltins() {
        val versions = layout.clangResourceRoot.listFiles()?.filter { it.isDirectory } ?: return
        val provided = versions.flatMap { version ->
            File(version, "lib").listFiles().orEmpty().filter { it.isDirectory && isWasmRuntimeDir(it.name) }
        }
        if (provided.isEmpty()) return
        versions.forEach { version ->
            provided.filter { it.parentFile.parentFile != version }.forEach { source ->
                val target = File(version, "lib/${source.name}")
                if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    runCatching {
                        target.parentFile.mkdirs()
                        Files.createSymbolicLink(target.toPath(), source.toPath())
                    }
                }
            }
        }
    }

    private fun isWasmRuntimeDir(name: String) = name.startsWith("wasm32") || name.startsWith("wasi")

    companion object {
        const val TARGET = "wasm32-wasip1"

        /** Termux package with the WASI sysroot and wasm builtins (clang and lld come with the toolchain). */
        const val PACKAGE = "wasi-libc"

        const val STACK_SIZE = 1024 * 1024

        /**
         * wasi-libc's opt-in POSIX emulations: signal(), clock()/times(), mmap() and getpid().
         * Each is a small static library, linked only when the program uses it.
         */
        val EMULATIONS = listOf("signal", "process-clocks", "mman", "getpid")
    }
}
