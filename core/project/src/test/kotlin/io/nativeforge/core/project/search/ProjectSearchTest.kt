package io.nativeforge.core.project.search

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectSearchTest {

    private val root: File = Files.createTempDirectory("nf-search").toFile()

    private fun file(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `finds matches with 1-based line and column and a preview`() {
        file("src/main.cpp", "int main() {\n    int count = 0;\n    return count;\n}\n")
        val result = ProjectSearch.search(root, SearchQuery("count"))
        assertEquals(2, result.totalMatches)
        val matches = result.files.single().matches
        assertEquals("src/main.cpp", result.files.single().relativePath)
        assertEquals(listOf(2 to 9, 3 to 12), matches.map { it.line to it.column })
        val first = matches.first()
        assertEquals("int count = 0;", first.preview)
        assertEquals("count", first.preview.substring(first.previewStart, first.previewEnd))
    }

    @Test
    fun `case, whole word and regex options`() {
        val text = "Count count counter COUNT"
        fun count(query: SearchQuery) = ProjectSearch.find(text, ProjectSearch.compile(query).getOrThrow()!!).size
        assertEquals(4, count(SearchQuery("count")))
        assertEquals(2, count(SearchQuery("count", matchCase = true)))
        assertEquals(3, count(SearchQuery("count", wholeWord = true)))
        assertEquals(1, count(SearchQuery("count", matchCase = true, wholeWord = true)))
        assertEquals(1, count(SearchQuery("c[a-z]+er", regex = true, matchCase = true)))
        // Without regex mode, regex characters are literal.
        assertEquals(0, count(SearchQuery("c.unt", matchCase = true)))
    }

    @Test
    fun `invalid regex is reported`() {
        assertTrue(ProjectSearch.compile(SearchQuery("(", regex = true)).isFailure)
    }

    @Test
    fun `skips build output, git, binaries and uses unsaved editor text`() {
        file("main.c", "needle on disk")
        file("build/out.c", "needle")
        file(".git/config", "needle")
        File(root, "lib.bin").writeBytes(byteArrayOf(0, 'n'.code.toByte(), 'e'.code.toByte()) + "needle".toByteArray())
        file("src/build/kept.c", "needle")
        val result = ProjectSearch.search(root, SearchQuery("needle"), textOf = { if (it.name == "main.c") "no match here" else null })
        assertEquals(listOf("src/build/kept.c"), result.files.map { it.relativePath })
    }

    @Test
    fun `stops at the match limit`() {
        file("a.txt", "x\n".repeat(10))
        file("b.txt", "x\n".repeat(10))
        val result = ProjectSearch.search(root, SearchQuery("x"), maxMatches = 15)
        assertEquals(15, result.totalMatches)
        assertTrue(result.truncated)
    }

    @Test
    fun `literal and regex replacement`() {
        assertEquals("b $1 b" to 2, ProjectSearch.replace("a $1 a", SearchQuery("a", wholeWord = true), "b"))
        assertEquals("x $1" to 1, ProjectSearch.replace("a $1", SearchQuery("a", wholeWord = true), "x"))
        assertEquals(
            "setY(getX()); setW(getV());" to 2,
            ProjectSearch.replace("setX(getY()); setV(getW());", SearchQuery("set(\\w)\\(get(\\w)", regex = true), "set$2(get$1"),
        )
        assertEquals("a\$b" to 1, ProjectSearch.replace("ab", SearchQuery("ab", regex = true), "a\\\$b"))
        val edits = ProjectSearch.replacements("one two one", SearchQuery("one"), "1")
        assertEquals(listOf(Triple(0, 3, "1"), Triple(8, 3, "1")), edits)
    }

    @Test
    fun `long lines are shortened around the hit`() {
        val line = "a".repeat(300) + "needle" + "b".repeat(300)
        val match = ProjectSearch.find(line, ProjectSearch.compile(SearchQuery("needle")).getOrThrow()!!).single()
        assertTrue(match.preview.startsWith("…") && match.preview.endsWith("…"))
        assertEquals("needle", match.preview.substring(match.previewStart, match.previewEnd))
        assertEquals(301, match.column)
    }
}
