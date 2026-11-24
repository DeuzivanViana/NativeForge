package io.nativeforge.core.remote

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectFilesTest {
    private val base = Files.createTempDirectory("nf-files").toFile()
    private val root = File(base, "project").apply { mkdirs() }
    private val secret = File(base, "secret.txt").apply { writeText("keys") }
    private val files = ProjectFiles(root, setOf(File(root, "build")))

    @Test
    fun `paths outside the project are refused`() {
        listOf("../secret.txt", "src/../../secret.txt", "/etc/passwd", "a/./b", "a//b", "x\u0000y", "..").forEach { path ->
            val status = assertFailsWith<FileAccessException>(path) { files.resolve(path) }.status
            assertTrue(status == 400 || status == 403, "$path gave $status")
        }
        assertFailsWith<FileAccessException> { files.resolve("") }
        assertEquals(files.root, files.resolve("", allowRoot = true))
    }

    @Test
    fun `symbolic links cannot escape the project`() {
        Files.createSymbolicLink(File(root, "link").toPath(), base.toPath())
        assertEquals(403, assertFailsWith<FileAccessException> { files.readText("link/secret.txt") }.status)
        assertFailsWith<FileAccessException> { files.write("link/new.txt", "x".toByteArray()) }
        assertFalse(File(base, "new.txt").exists())
        assertEquals("keys", secret.readText())
    }

    @Test
    fun `write, read, rename and delete inside the project`() {
        val modified = files.write("src/main.c", "int main(){}".toByteArray())
        assertEquals("int main(){}", files.readText("src/main.c").text)
        assertFailsWith<FileAccessException> { files.write("src/main.c", "x".toByteArray(), expectedModified = modified - 5000) }
        files.rename("src/main.c", "src/app.c")
        assertTrue(File(root, "src/app.c").isFile)
        files.createDirectory("assets/img")
        files.createFile("assets/img/a.txt")
        files.delete("assets")
        assertFalse(File(root, "assets").exists())
        assertFalse(File(root, "src").listFiles()!!.any { it.name.endsWith(".nf-tmp") })
    }

    @Test
    fun `binary files are reported, not decoded`() {
        File(root, "a.bin").writeBytes(byteArrayOf(1, 0, 2))
        assertNull(files.readText("a.bin").text)
    }

    @Test
    fun `tree skips git, build and cache folders`() {
        File(root, ".git/objects").mkdirs()
        File(root, "build/debug").mkdirs()
        File(root, "src").mkdirs()
        File(root, "src/main.cpp").writeText("")
        File(root, ".clang-format").writeText("")
        val paths = files.tree().map { it.path }
        assertEquals(listOf("src", "src/main.cpp", ".clang-format"), paths)
    }

    @Test
    fun `byte ring keeps the newest bytes`() {
        val ring = ByteRing(4)
        ring.write("ab".toByteArray())
        ring.write("cdef".toByteArray())
        assertEquals("cdef", String(ring.toByteArray()))
        ring.write("0123456789".toByteArray(), 2, 3)
        assertEquals("f234", String(ring.toByteArray()))
    }
}
