package io.nativeforge.core.packages

import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.source.JsonIndexSource
import io.nativeforge.core.toolchain.Abi
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Archives from a repository are installed only when the index lets them be checked. */
class JsonIndexSourceTest {

    private fun entry(name: String, url: String, sha256: String?) =
        """{"name":"$name","version":"1","architecture":"aarch64","downloadUrl":"$url",""" +
            (sha256?.let { "\"sha256\":\"$it\"," } ?: "") + """"format":"tar.xz","target":"isolated"}"""

    private fun source(indexUrl: String, vararg entries: String) = JsonIndexSource(
        object : HttpClient {
            override suspend fun getBytes(url: String) = """{"packages":[${entries.joinToString(",")}]}""".toByteArray()
            override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) = Unit
        },
        indexUrl,
    )

    @Test
    fun `entries without a checksum or over plain http are dropped`() = runBlocking {
        val sha = "a".repeat(64)
        val names = source(
            "https://example.org/repo/index.json",
            entry("relative", "aarch64/relative.tar.xz", sha),
            entry("absolute", "https://cdn.example.org/absolute.tar.xz", sha),
            entry("no-checksum", "aarch64/no-checksum.tar.xz", null),
            entry("plain-http", "http://example.org/plain-http.tar.xz", sha),
            entry("local-file", "file:///sdcard/local-file.tar.xz", sha),
            entry("bundled-asset", "asset:packages/x.tar.xz", sha),
        ).fetchIndex(Abi.ARM64_V8A).map { it.name }
        assertEquals(listOf("relative", "absolute"), names)
    }

    @Test
    fun `the bundled index serves its archives from the apk`() = runBlocking {
        val info = source("asset:packages/index.json", entry("sdl3", "asset:packages/aarch64/sdl3.tar.xz", "b".repeat(64)))
            .fetchIndex(Abi.ARM64_V8A).single()
        assertEquals("asset:packages/aarch64/sdl3.tar.xz", info.downloadUrl)
    }
}
