package io.nativeforge.core.packages

import io.nativeforge.core.packages.install.Checksums
import io.nativeforge.core.packages.install.InstalledPackageDatabase
import io.nativeforge.core.packages.model.ArchiveFormat
import io.nativeforge.core.packages.model.InstallTarget
import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.source.PackageSource
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ToolchainLayout
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackageCliTest {

    private val root: File = Files.createTempDirectory("nf-cli").toFile()
    private val layout = ToolchainLayout(root)
    private val archives = File(root, "archives").apply { mkdirs() }

    /** A tar.gz holding one script at `bin/<name>` that mentions Termux's prefix. */
    private fun archive(name: String): File {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { tar ->
            val data = "#!${ToolchainLayout.TERMUX_PREFIX}/bin/sh\nmkdir -p ${ToolchainLayout.TERMUX_FILES}/home/.$name\n".toByteArray()
            tar.putArchiveEntry(TarArchiveEntry("bin/$name").apply { size = data.size.toLong(); mode = 0b111_101_101 })
            tar.write(data)
            tar.closeArchiveEntry()
        }
        return File(archives, "$name.tar.gz").apply { writeBytes(bytes.toByteArray()) }
    }

    private inner class Source(var version: String = "1") : PackageSource {
        override val id = "termux"
        override val displayName = "Termux"
        var fail = false
        override suspend fun fetchIndex(abi: Abi): List<PackageInfo> {
            if (fail) throw IOException("offline")
            return listOf(
                pkg("bash", "GNU Bourne Again shell", listOf("readline")),
                pkg("readline", "Line editing library"),
                pkg("nano", "Small, friendly text editor"),
            )
        }

        private fun pkg(name: String, description: String, depends: List<String> = emptyList()): PackageInfo {
            val file = archive(name)
            return PackageInfo(
                name = name, version = version, description = description, architecture = "aarch64",
                depends = depends, downloadUrl = file.path, sha256 = Checksums.sha256(file),
                format = ArchiveFormat.TAR_GZ, target = InstallTarget.PREFIX, sourceId = id,
            )
        }
    }

    private val localFiles = object : HttpClient {
        override suspend fun getBytes(url: String): ByteArray = File(url).readBytes()
        override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
            destination.parentFile?.mkdirs()
            File(url).copyTo(destination, overwrite = true)
        }
    }

    private val source = Source()
    private val database = InstalledPackageDatabase(File(root, "packages/installed.json"))
    private val manager = PackageManager(listOf(source), localFiles, layout, database, Abi.ARM64_V8A)
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private var changes = 0
    private val cli = PackageCli(manager, PrintStream(out, true), PrintStream(err, true)) { changes++ }

    private fun run(vararg args: String): Int = runBlocking { cli.run(args.toList()) }
    private fun stdout() = out.toString().also { out.reset() }

    @Test
    fun `install resolves dependencies, relocates scripts and runs the after-change hook`() {
        assertEquals(0, run("install", "-y", "bash"))
        val text = stdout()
        assertTrue("Installing 2 package(s): readline bash" in text, text)
        val script = File(layout.binDir, "bash").readText()
        assertTrue(script.startsWith("#!${layout.prefix.path}/bin/sh"), script)
        assertTrue("mkdir -p ${layout.homeDir.path}/.bash" in script, script)
        assertEquals(1, changes)

        assertEquals(0, run("install", "bash"))
        assertTrue("bash is already installed." in stdout())
        assertEquals(1, changes)
    }

    @Test
    fun `search, show and list`() {
        run("install", "nano")
        stdout()
        assertEquals(0, run("search", "editor"))
        assertTrue("nano/1 [installed]" in stdout())
        assertEquals(0, run("show", "bash"))
        val shown = stdout()
        assertTrue("Depends: readline" in shown && "Installed: no" in shown, shown)
        assertEquals(0, run("list-installed"))
        assertEquals("nano/1", stdout().trim())
        assertEquals(1, run("show", "vim"))
    }

    @Test
    fun `upgrade reinstalls packages with a newer version`() {
        run("install", "nano")
        source.version = "2"
        stdout()
        assertEquals(0, run("upgrade"))
        assertTrue("[1/1] nano 2" in stdout())
        assertEquals("2", database.get("nano")?.info?.version)
    }

    @Test
    fun `uninstall removes the files`() {
        run("install", "nano")
        assertEquals(0, run("remove", "nano"))
        assertFalse(File(layout.binDir, "nano").exists())
        assertEquals(1, run("remove", "nano"))
    }

    @Test
    fun `failures and unknown commands exit with 1`() {
        source.fail = true
        assertEquals(1, run("install", "nano"))
        assertTrue(err.toString().isNotBlank())
        assertEquals(1, run("frobnicate"))
        assertEquals(1, run())
        assertEquals(0, run("help"))
    }

    @Test
    fun `the database sees writes made by another process`() {
        val other = InstalledPackageDatabase(File(root, "packages/installed.json"))
        assertTrue(other.names().isEmpty())
        run("install", "nano")
        assertEquals(setOf("nano"), other.names())
    }
}
