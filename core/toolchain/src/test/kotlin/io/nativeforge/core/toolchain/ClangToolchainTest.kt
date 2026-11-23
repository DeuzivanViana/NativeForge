package io.nativeforge.core.toolchain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClangToolchainTest {

    private val layout = ToolchainLayout(File("/data/user/0/io.nativeforge/files"))
    private val toolchain = ClangToolchain(layout, Abi.ARM64_V8A, apiLevel = 24)

    @Test
    fun `compile flags target the device abi and sysroot`() {
        val flags = toolchain.baseCompileFlags()
        assertTrue("--target=aarch64-linux-android24" in flags)
        assertTrue("--sysroot=/data/user/0/io.nativeforge/files" in flags)
    }

    @Test
    fun `environment exposes the prefix to child processes`() {
        val env = toolchain.environment()
        assertEquals("/data/user/0/io.nativeforge/files/usr/lib", env["LD_LIBRARY_PATH"])
        assertTrue(env.getValue("PATH").startsWith("/data/user/0/io.nativeforge/files/usr/bin"))
    }

    @Test
    fun `preferred abi follows device order`() {
        assertEquals(Abi.ARM64_V8A, Abi.preferred(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(Abi.X86_64, Abi.preferred(listOf("mips", "x86_64")))
    }
}
