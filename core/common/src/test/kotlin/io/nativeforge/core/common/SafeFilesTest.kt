package io.nativeforge.core.common

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafeFilesTest {
    private val root: File = Files.createTempDirectory("nf-safe").toFile()

    @Test
    fun `deleting a tree never follows links`() {
        val precious = File(root, "precious").apply { mkdirs() }
        File(precious, "keep.txt").writeText("keep")
        val project = File(root, "project").apply { mkdirs() }
        File(project, "main.c").writeText("int main(){}")
        Files.createSymbolicLink(File(project, "link").toPath(), precious.toPath())

        assertTrue(SafeFiles.deleteTree(project))
        assertFalse(project.exists())
        assertEquals("keep", File(precious, "keep.txt").readText())
    }

    @Test
    fun `regular files skip links and resolveInside rejects escapes`() {
        val assets = File(root, "assets").apply { mkdirs() }
        File(assets, "a.png").writeText("x")
        val secret = File(root, "secret.p12").apply { writeText("key") }
        Files.createSymbolicLink(File(assets, "b.png").toPath(), secret.toPath())
        assertEquals(listOf("a.png"), SafeFiles.regularFiles(assets).map { it.name })
        assertNull(SafeFiles.resolveInside(assets, "../secret.p12"))
        assertNull(SafeFiles.resolveInside(assets, "b.png"))
        assertEquals(File(assets, "a.png"), SafeFiles.resolveInside(assets, "a.png"))
    }
}
