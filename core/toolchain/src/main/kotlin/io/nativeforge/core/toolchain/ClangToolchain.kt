package io.nativeforge.core.toolchain

import java.io.File

/**
 * LLVM/clang toolchain installed from Termux packages into [layout].
 *
 * Termux binaries contain the hard-coded prefix `/data/data/com.termux/files/usr`.
 * Instead of patching ELF files, we pass the sysroot explicitly and point
 * `LD_LIBRARY_PATH` at our prefix so the dynamic loader finds the toolchain's
 * own shared libraries.
 */
class ClangToolchain(
    private val layout: ToolchainLayout,
    override val abi: Abi,
    override val apiLevel: Int = DEFAULT_API_LEVEL,
) : Toolchain {

    override val cCompiler: File get() = File(layout.binDir, "clang")
    override val cxxCompiler: File get() = File(layout.binDir, "clang++")
    override val cmake: File get() = File(layout.binDir, "cmake")
    override val ninja: File get() = File(layout.binDir, "ninja")
    override val make: File get() = File(layout.binDir, "make")
    override val shell: File get() = File("/system/bin/sh")

    /** Termux's `ccache` package, when the user installed it. */
    override val compilerCache: File? get() = File(layout.binDir, "ccache").takeIf { it.isFile }

    override val supportsSplitDwarf: Boolean get() = true

    /** `ld.lld` comes with the `lld` package of [REQUIRED_PACKAGES]. */
    private val hasLld: Boolean get() = File(layout.binDir, "ld.lld").exists()

    override val supportsThinLto: Boolean get() = hasLld

    /**
     * clang reads `<driver>.cfg` next to its binary. Writing the sysroot there means
     * `clang` works even when Makefiles, build.ninja files or CMake projects replace
     * CFLAGS/LDFLAGS: Termux's built-in sysroot path does not exist in this app.
     */
    override fun prepare() {
        if (!cCompiler.exists()) return
        // ninja, make and cmake call Termux's /data/data/com.termux/.../bin/sh (see TermuxShellPatch).
        runCatching { TermuxShellPatch.patchDirectory(layout.binDir, File(layout.prefix, "libexec/nativeforge/shell-patch.txt")) }
        // Scripts whose #! line was relocated to our prefix look for sh there.
        runCatching {
            val sh = File(layout.binDir, "sh").toPath()
            if (!java.nio.file.Files.exists(sh, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                java.nio.file.Files.createSymbolicLink(sh, java.nio.file.Paths.get(shell.path))
            }
        }
        val config = (driverFlags() + "-Wno-unused-command-line-argument").joinToString("\n", postfix = "\n")
        listOf("clang.cfg", "clang++.cfg", "cc.cfg", "c++.cfg").forEach { name ->
            val file = File(layout.binDir, name)
            if (!file.isFile || file.readText() != config) file.writeText(config)
        }
    }

    private fun driverFlags(): List<String> = listOf(
        "--target=${abi.targetTriple}$apiLevel",
        "--sysroot=${layout.sysroot.path}",
        "-L${layout.libDir.path}",
        "-Wl,-rpath,${layout.libDir.path}",
    )

    override fun environment(): Map<String, String> = mapOf(
        "PREFIX" to layout.prefix.path,
        "HOME" to layout.homeDir.path,
        "TMPDIR" to layout.tmpDir.path,
        "PATH" to "${layout.binDir.path}:/system/bin",
        "LD_LIBRARY_PATH" to layout.libDir.path,
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
        "PKG_CONFIG_PATH" to File(layout.libDir, "pkgconfig").path,
        // Termux packages have Termux's prefix compiled in; point them at ours.
        "TERMINFO" to File(layout.prefix, "share/terminfo").path,
        "INPUTRC" to File(layout.prefix, "etc/inputrc").path,
        "SSL_CERT_FILE" to File(layout.prefix, "etc/tls/cert.pem").path,
        "CURL_CA_BUNDLE" to File(layout.prefix, "etc/tls/cert.pem").path,
        "GIT_EXEC_PATH" to File(layout.prefix, "libexec/git-core").path,
        "GIT_TEMPLATE_DIR" to File(layout.prefix, "share/git-core/templates").path,
        "TERMUX_PREFIX" to layout.prefix.path,
        // Colored diagnostics are parsed from plain text; keep them off for tools.
        "CLICOLOR_FORCE" to "0",
        // ccache (when installed): a bounded cache in the app's home. Precompiled headers and
        // __DATE__/__TIME__ would otherwise make every compile that uses them a miss.
        "CCACHE_DIR" to File(layout.homeDir, ".cache/ccache").path,
        "CCACHE_MAXSIZE" to CCACHE_MAX_SIZE,
        "CCACHE_SLOPPINESS" to "pch_defines,time_macros",
        "CCACHE_TEMPDIR" to layout.tmpDir.path,
    )

    override fun baseCompileFlags(): List<String> = listOf(
        "--target=${abi.targetTriple}$apiLevel",
        "--sysroot=${layout.sysroot.path}",
        "-fdiagnostics-color=never",
    )

    override fun baseLinkFlags(): List<String> = buildList {
        add("--target=${abi.targetTriple}$apiLevel")
        add("--sysroot=${layout.sysroot.path}")
        add("-L${layout.libDir.path}")
        add("-Wl,-rpath,${layout.libDir.path}")
        // lld links on several threads and does ThinLTO itself; say so even if clang's default changes.
        if (hasLld) add("-fuse-ld=lld")
    }

    override fun runtimeLibraries(): List<File> = listOf(File(layout.libDir, "libc++_shared.so"))

    override fun missingComponents(): List<String> =
        listOf(cCompiler, cxxCompiler, cmake, ninja)
            .filterNot { it.exists() }
            .map { it.name }

    companion object {
        const val DEFAULT_API_LEVEL = 24

        /** Upper bound of the ccache folder; ccache evicts the oldest entries beyond it. */
        const val CCACHE_MAX_SIZE = "512M"

        /** Termux packages that make up a complete toolchain (names as in the Termux main repository). */
        val REQUIRED_PACKAGES = listOf("clang", "lld", "cmake", "ninja", "make", "pkg-config", "ndk-sysroot", "libc++", "ccache")
    }
}
