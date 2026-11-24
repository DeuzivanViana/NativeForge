package io.nativeforge.core.export

import io.nativeforge.core.common.NfError
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.export.web.WebExportRequest
import io.nativeforge.core.export.web.WebExporter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WebExporterTest {

    private val dir = createTempDirectory("web-export").toFile()
    private val module = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0, 42)
    private val wasm = File(dir, "game").apply { writeBytes(module) }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun request(title: String = "My Game", assets: File? = null, arguments: List<String> = emptyList()) = WebExportRequest(
        wasm = wasm,
        title = title,
        programName = "my game",
        arguments = arguments,
        assetsDirectory = assets,
        outputDirectory = File(dir, "out/web/my_game"),
        outputZip = File(dir, "out/my_game-web.zip"),
    )

    @Test
    fun `writes a self-contained page, the module and a zip`() {
        val result = WebExporter().export(request(arguments = listOf("--level", "2")))
        val export = assertIs<Outcome.Success<io.nativeforge.core.export.web.WebExport>>(result).value

        val page = export.page.readText()
        assertTrue(page.contains(Base64.getEncoder().encodeToString(module)))
        assertTrue(page.contains("<title>My Game</title>"))
        assertTrue(page.contains("\"args\":[\"--level\",\"2\"]"))
        assertFalse(page.contains("{{"))
        assertTrue(File(dir, "out/web/my_game/my_game.wasm").readBytes().contentEquals(module))

        ZipFile(export.zip).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(listOf("my_game/index.html", "my_game/my_game.wasm", "my_game/README.txt"), names)
            assertEquals(page, zip.getInputStream(zip.getEntry("my_game/index.html")).readBytes().decodeToString())
        }
    }

    @Test
    fun `content security policy pins the inline script and style and allows no network`() {
        val page = (WebExporter().export(request()) as Outcome.Success).value.page.readText()
        val csp = Regex("""http-equiv="Content-Security-Policy" content="([^"]+)"""").find(page)!!.groupValues[1]
        assertTrue(csp.startsWith("default-src 'none'"))
        assertFalse(csp.contains("unsafe-inline"))
        assertFalse(csp.contains("connect-src"))

        val script = Regex("""<script>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
        val style = Regex("""<style>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
        assertTrue(csp.contains("script-src 'sha256-${sha256(script)}' 'wasm-unsafe-eval'"))
        assertTrue(csp.contains("style-src 'sha256-${sha256(style)}'"))
    }

    @Test
    fun `user text cannot break out of the page markup`() {
        val page = (WebExporter().export(request(title = "</title><script>x</script>", arguments = listOf("</script><b>"))) as Outcome.Success)
            .value.page.readText()
        assertTrue(page.contains("<title>&lt;/title&gt;&lt;script&gt;x&lt;/script&gt;</title>"))
        assertTrue(page.contains("\\u003c/script\\u003e\\u003cb\\u003e"))
        // Only the runtime's own script element is executable.
        assertEquals(1, Regex("<script>").findAll(page).count())
    }

    @Test
    fun `placeholders inside user text are left alone`() {
        val page = (WebExporter().export(request(title = "{{WASM}} {{SCRIPT}}")) as Outcome.Success).value.page.readText()
        assertTrue(page.contains("<title>{{WASM}} {{SCRIPT}}</title>"))
    }

    @Test
    fun `embeds assets but skips links that leave the folder`() {
        val assets = File(dir, "assets").apply { mkdirs() }
        File(assets, "maps").mkdirs()
        File(assets, "maps/level1.txt").writeText("####")
        val secret = File(dir, "secret.txt").apply { writeText("private") }
        Files.createSymbolicLink(File(assets, "link.txt").toPath(), secret.toPath())

        val page = (WebExporter().export(request(assets = assets)) as Outcome.Success).value.page.readText()
        assertTrue(page.contains("{\"path\":\"assets/maps/level1.txt\",\"data\":\"${Base64.getEncoder().encodeToString("####".toByteArray())}\"}"))
        assertFalse(page.contains("link.txt"))
        assertFalse(page.contains(Base64.getEncoder().encodeToString("private".toByteArray())))
    }

    @Test
    fun `rejects files that are not wasm and oversized assets`() {
        wasm.writeText("#!/bin/sh")
        assertIs<NfError.Validation>((WebExporter().export(request()) as Outcome.Failure).error)

        wasm.writeBytes(module)
        val assets = File(dir, "assets").apply { mkdirs() }
        File(assets, "big.bin").writeBytes(ByteArray(2048))
        val small = WebExporter(WebExporter.Limits(maxAssetBytes = 1024))
        assertIs<NfError.Validation>((small.export(request(assets = assets)) as Outcome.Failure).error)
    }

    @Test
    fun `file names are safe`() {
        assertEquals("my_game", WebExporter.fileName("my game"))
        assertEquals("program", WebExporter.fileName("../.."))
        assertEquals("a-b.c", WebExporter.fileName("a-b.c"))
    }

    private fun sha256(text: String) =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
}
