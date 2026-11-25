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

    @Test
    fun `atomic writes replace the content, keep the executable bit and leave no temporary file`() {
        val dir = java.nio.file.Files.createTempDirectory("nf-atomic").toFile()
        val file = File(dir, "run.sh").apply { writeText("old"); setExecutable(true) }
        SafeFiles.writeTextAtomically(file, "new")
        kotlin.test.assertEquals("new", file.readText())
        kotlin.test.assertTrue(file.canExecute())
        kotlin.test.assertEquals(listOf("run.sh"), dir.list()!!.toList())

        val created = File(dir, "sub/new.c")
        SafeFiles.writeTextAtomically(created, "int x;")
        kotlin.test.assertEquals("int x;", created.readText())
    }

    @Test
    fun `atomic writes go through a symbolic link`() {
        val dir = java.nio.file.Files.createTempDirectory("nf-atomic-link").toFile()
        val target = File(dir, "real.c").apply { writeText("old") }
        val link = File(dir, "link.c")
        java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        SafeFiles.writeTextAtomically(link, "new")
        kotlin.test.assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        kotlin.test.assertEquals("new", target.readText())
    }
}
