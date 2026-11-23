package io.nativeforge.core.toolchain

import java.io.File

/**
 * On-device directory layout. It mirrors Termux (`<files>/usr` as prefix) so that
 * packages built for Termux find their headers and libraries through
 * `--sysroot=<files>`, which makes clang look in `<files>/usr/include` and
 * `<files>/usr/lib`.
 */
class ToolchainLayout(val filesDir: File) {
    val sysroot: File get() = filesDir
    val prefix: File get() = File(filesDir, "usr")
    val binDir: File get() = File(prefix, "bin")
    val libDir: File get() = File(prefix, "lib")
    val includeDir: File get() = File(prefix, "include")
    val tmpDir: File get() = File(prefix, "tmp")
    val homeDir: File get() = File(filesDir, "home")

    /** Android-targeted libraries (SDL, raylib...) live outside the Termux prefix. */
    val androidLibrariesDir: File get() = File(filesDir, "android-libs")

    fun ensureDirectories() {
        listOf(prefix, binDir, libDir, tmpDir, homeDir, androidLibrariesDir).forEach { it.mkdirs() }
    }

    companion object {
        /** Prefix hard-coded inside packages built for Termux. */
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    }
}
