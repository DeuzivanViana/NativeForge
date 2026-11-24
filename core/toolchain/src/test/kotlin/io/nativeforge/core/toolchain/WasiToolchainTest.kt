package io.nativeforge.core.toolchain

import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasiToolchainTest {

    private val files = createTempDirectory("wasi").toFile()
    private val layout = ToolchainLayout(files)
    private val host = ClangToolchain(layout, Abi.ARM64_V8A)
    private val toolchain = WasiToolchain(host, layout)

    @AfterTest
    fun cleanUp() {
        files.deleteRecursively()
    }

    @Test
    fun `flags target wasi and ignore the android clang config`() {
        val compile = toolchain.baseCompileFlags()
        assertEquals("--no-default-config", compile.first())
        assertTrue("--target=wasm32-wasip1" in compile)
        assertTrue("--sysroot=${File(files, "usr/share/wasi-sysroot").path}" in compile)
        assertTrue("-D_WASI_EMULATED_PROCESS_CLOCKS" in compile)
        assertFalse(compile.any { it.contains("android") })

        val link = toolchain.baseLinkFlags()
        assertTrue("--no-default-config" in link)
        assertTrue("-lwasi-emulated-signal" in link)
        assertFalse(link.any { it.startsWith("-L") || it.contains("rpath") })
    }

    @Test
    fun `reuses the host compilers and environment`() {
        assertEquals(host.cCompiler, toolchain.cCompiler)
        assertEquals(host.environment(), toolchain.environment())
        assertTrue(toolchain.runtimeLibraries().isEmpty())
    }

    @Test
    fun `reports what is missing until the sysroot and linker are installed`() {
        assertEquals(listOf("clang", "wasm-ld (lld)", "WASI sysroot (wasi-libc)"), toolchain.missingComponents())

        File(files, "usr/bin").mkdirs()
        File(files, "usr/bin/clang").writeText("")
        File(files, "usr/bin/wasm-ld").writeText("")
        File(files, "usr/share/wasi-sysroot/lib/wasm32-wasip1").mkdirs()
        File(files, "usr/share/wasi-sysroot/lib/wasm32-wasip1/libc.a").writeText("")
        assertTrue(toolchain.missingComponents().isEmpty())
    }

    @Test
    fun `links wasm builtins into other clang versions`() {
        val builtins = File(files, "usr/lib/clang/22/lib/wasm32-unknown-wasip1")
        builtins.mkdirs()
        File(builtins, "libclang_rt.builtins.a").writeText("rt")
        File(files, "usr/lib/clang/21/include").mkdirs()
        File(files, "usr/lib/clang/21/lib/linux").mkdirs()

        toolchain.linkBuiltins()
        toolchain.linkBuiltins() // idempotent

        val linked = File(files, "usr/lib/clang/21/lib/wasm32-unknown-wasip1")
        assertTrue(Files.isSymbolicLink(linked.toPath()))
        assertEquals("rt", File(linked, "libclang_rt.builtins.a").readText())
        assertFalse(File(files, "usr/lib/clang/21/lib/linux/wasm32-unknown-wasip1").exists())
        assertFalse(Files.isSymbolicLink(builtins.toPath()))
    }

    @Test
    fun `web builds keep only headers outside the termux prefix`() {
        val glm = NativeDependency(
            name = "glm",
            includeDirectories = listOf(File(files, "android-libs/glm/include")),
            libraryDirectories = listOf(File(files, "android-libs/glm/lib")),
            linkLibraries = listOf("glm"),
            runtimeLibraries = listOf(File(files, "android-libs/glm/lib/libglm.so")),
        )
        val gmp = NativeDependency("libgmp", includeDirectories = listOf(File(files, "usr/include")), linkLibraries = listOf("gmp"))

        val web = toolchain.webDependencies(listOf(glm, gmp))

        assertEquals(listOf(NativeDependency("glm", includeDirectories = listOf(File(files, "android-libs/glm/include")))), web)
    }
}
