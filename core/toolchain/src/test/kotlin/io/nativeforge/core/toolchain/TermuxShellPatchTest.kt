package io.nativeforge.core.toolchain

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TermuxShellPatchTest {

    private val termuxSh = "/data/data/com.termux/files/usr/bin/sh"

    private fun elf(vararg strings: String): ByteArray {
        val body = strings.joinToString("") { it + "\u0000" }.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0) + body
    }

    @Test
    fun `the shell path becomes system sh with the same length and tail`() {
        val replacement = TermuxShellPatch.replacement
        assertEquals(termuxSh.length, replacement.length)
        assertTrue(replacement.endsWith("/bin/sh"))
        assertEquals("/system/bin/sh", replacement.replace(Regex("/+"), "/"))
    }

    @Test
    fun `ninja make and cmake strings are patched but longer paths are not`() {
        val bytes = elf(termuxSh, "SHELL = $termuxSh\n\n", "$termuxSh-wrapper", "${termuxSh}a256sum", "/bin/sh")
        assertEquals(2, TermuxShellPatch.patch(bytes))
        val text = bytes.decodeToString()
        assertFalse("$termuxSh\u0000" in text || "$termuxSh\n" in text)
        assertTrue("SHELL = ${TermuxShellPatch.replacement}\n" in text)
        assertTrue("$termuxSh-wrapper" in text && "${termuxSh}a256sum" in text)
    }

    @Test
    fun `binaries in the prefix are patched once and remembered`() {
        val root = Files.createTempDirectory("nf-shpatch").toFile()
        val bin = File(root, "bin").apply { mkdirs() }
        val ninja = File(bin, "ninja").apply { writeBytes(elf(termuxSh)); setExecutable(true) }
        File(bin, "script").writeText("#!$termuxSh\n")
        val stamp = File(root, "stamp.txt")

        assertEquals(listOf("ninja"), TermuxShellPatch.patchDirectory(bin, stamp))
        assertTrue(ninja.canExecute())
        assertTrue(TermuxShellPatch.replacement in ninja.readBytes().decodeToString())
        assertTrue("#!$termuxSh" in File(bin, "script").readText(), "text files are the relocator's job")
        assertEquals(emptyList(), TermuxShellPatch.patchDirectory(bin, stamp))
    }
}
