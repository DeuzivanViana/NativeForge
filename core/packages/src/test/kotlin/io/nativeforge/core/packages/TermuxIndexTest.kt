package io.nativeforge.core.packages

import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.net.HttpException
import io.nativeforge.core.packages.source.DebControlParser
import io.nativeforge.core.packages.source.TermuxAptSource
import io.nativeforge.core.toolchain.Abi
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TermuxIndexTest {

    private val index = """
        Package: clang
        Architecture: aarch64
        Version: 19.1.7
        Depends: libc++, libllvm (= 19.1.7), ndk-sysroot
        Filename: pool/main/c/clang/clang_19.1.7_aarch64.deb
        Size: 30000000
        SHA256: abc123
        Description: C language frontend for LLVM
         Long description line.

        Package: ninja
        Architecture: aarch64
        Version: 1.12.1
        Filename: pool/main/n/ninja/ninja_1.12.1_aarch64.deb
        Description: A small build system with a focus on speed
    """.trimIndent()

    @Test
    fun `control parser handles continuation lines`() {
        val stanzas = DebControlParser.parse(index)
        assertEquals(2, stanzas.size)
        assertEquals("C language frontend for LLVM\nLong description line.", stanzas[0]["Description"])
    }

    @Test
    fun `termux source falls back to the plain index and builds urls`() = runBlocking<Unit> {
        val http = object : HttpClient {
            override suspend fun getBytes(url: String): ByteArray =
                if (url.endsWith("/Packages")) index.toByteArray() else throw HttpException(404, url)
            override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) = error("unused")
        }
        val packages = TermuxAptSource(http, baseUrl = "https://mirror.test/termux-main").fetchIndex(Abi.ARM64_V8A)

        val clang = packages.first { it.name == "clang" }
        assertEquals("https://mirror.test/termux-main/pool/main/c/clang/clang_19.1.7_aarch64.deb", clang.downloadUrl)
        assertEquals(listOf("libc++", "libllvm (= 19.1.7)", "ndk-sysroot"), clang.depends)
        assertEquals("data/data/com.termux/files/usr", clang.stripPrefix)
        assertEquals(30000000L, clang.sizeBytes)
    }
}
