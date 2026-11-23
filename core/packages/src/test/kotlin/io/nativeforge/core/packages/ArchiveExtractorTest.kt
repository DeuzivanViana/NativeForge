package io.nativeforge.core.packages

import io.nativeforge.core.packages.install.ArchiveExtractor
import io.nativeforge.core.packages.install.PrefixRelocator
import io.nativeforge.core.packages.model.ArchiveFormat
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveExtractorTest {

    private val temp: File = Files.createTempDirectory("nf-extract").toFile()
    private val termuxPrefix = "data/data/com.termux/files/usr/"

    private fun tarXz(build: TarArchiveOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(bytes)).use { it.build() }
        return bytes.toByteArray()
    }

    private fun TarArchiveOutputStream.file(name: String, content: String, mode: Int = 0b110_100_100) {
        val data = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong(); this.mode = mode })
        write(data)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.symlink(name: String, target: String) {
        putArchiveEntry(TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { linkName = target })
        closeArchiveEntry()
    }

    private fun deb(dataTarXz: ByteArray): File {
        val file = File(temp, "pkg.deb")
        ArArchiveOutputStream(file.outputStream()).use { ar ->
            listOf("debian-binary" to "2.0\n".toByteArray(), "data.tar.xz" to dataTarXz).forEach { (name, data) ->
                ar.putArchiveEntry(ArArchiveEntry(name, data.size.toLong()))
                ar.write(data)
                ar.closeArchiveEntry()
            }
        }
        return file
    }

    @Test
    fun `extracts deb payload, strips prefix and keeps symlinks and exec bits`() {
        val archive = deb(
            tarXz {
                file("./${termuxPrefix}bin/clang-19", "#!/data/data/com.termux/files/usr/bin/sh\necho clang\n", 0b111_101_101)
                symlink("./${termuxPrefix}bin/clang", "clang-19")
                file("./${termuxPrefix}lib/pkgconfig/zlib.pc", "prefix=/data/data/com.termux/files/usr\n")
                file("./data/data/com.termux/files/home/ignored.txt", "outside prefix")
            },
        )
        val destination = File(temp, "usr")
        val strip = termuxPrefix
        val files = ArchiveExtractor().extract(archive, ArchiveFormat.DEB, destination) { path ->
            if (path.startsWith(strip)) path.removePrefix(strip) else null
        }

        assertEquals(setOf("bin/clang-19", "bin/clang", "lib/pkgconfig/zlib.pc"), files.toSet())
        assertTrue(File(destination, "bin/clang-19").canExecute())
        assertTrue(Files.isSymbolicLink(File(destination, "bin/clang").toPath()))

        val rewritten = PrefixRelocator("/data/data/com.termux/files/usr", destination.path).relocate(destination, files)
        assertEquals(2, rewritten)
        assertEquals("prefix=${destination.path}\n", File(destination, "lib/pkgconfig/zlib.pc").readText())
    }

    @Test
    fun `rejects entries escaping the destination`() {
        val archive = File(temp, "evil.tar.xz").apply { writeBytes(tarXz { file("../../evil.txt", "x") }) }
        assertFailsWith<IOException> {
            ArchiveExtractor().extract(archive, ArchiveFormat.TAR_XZ, File(temp, "out"))
        }
    }

    @Test
    fun `a file entry cannot be written through a planted symlink`() {
        val outside = File(temp, "outside.txt").apply { writeText("original") }
        val archive = File(temp, "evil.tar.xz").apply {
            writeBytes(
                tarXz {
                    symlink("lib/evil", outside.path)
                    file("lib/evil", "overwritten")
                },
            )
        }
        val destination = File(temp, "dest")
        ArchiveExtractor().extract(archive, ArchiveFormat.TAR_XZ, destination)
        assertEquals("original", outside.readText())
        assertEquals("overwritten", File(destination, "lib/evil").readText())
    }
}
