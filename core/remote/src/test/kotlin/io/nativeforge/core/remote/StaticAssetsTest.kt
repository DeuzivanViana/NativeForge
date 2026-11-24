package io.nativeforge.core.remote

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticAssetsTest {
    private val webDir = File("src/main/resources/io/nativeforge/core/remote/web")

    @Test
    fun `manifest lists every web file`() {
        val listed = webDir.resolve("MANIFEST").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        val actual = webDir.walkTopDown().filter { it.isFile && it.name != "MANIFEST" }.map { it.relativeTo(webDir).invariantSeparatorsPath }.toSet()
        assertEquals(actual, listed, "Update web/MANIFEST (it versions the cached assets)")
    }

    @Test
    fun `index html gets the versioned prefix and its import map hash`() {
        val assets = StaticAssets()
        assertTrue(assets.indexHtml.contains(assets.prefix + "js/app.js"))
        assertTrue("{{ASSETS}}" !in assets.indexHtml)
        assertTrue(assets.importMapHash.startsWith("'sha256-"))
        assertEquals(1, Regex("<script(?! type=\"module\" src)").findAll(assets.indexHtml).count(), "only the import map may be inline")
    }

    @Test
    fun `every vendored import is mapped`() {
        val index = webDir.resolve("index.html").readText()
        val mapped = Regex("\"([@\\w/.-]+)\": \"\\{\\{ASSETS}}").findAll(index).map { it.groupValues[1] }.toSet()
        val imported = webDir.walkTopDown().filter { it.extension in setOf("js", "mjs") }.flatMap { file ->
            Regex("(?m)^(?:import|export)\\b[^;'\"\\n]*\\bfrom\\s*['\"]([^'\"./][^'\"]*)['\"]").findAll(file.readText()).map { it.groupValues[1] }
        }.toSet()
        assertTrue(mapped.containsAll(imported), "unmapped: ${imported - mapped}")
    }
}
