package io.nativeforge.core.packages

import io.nativeforge.core.packages.install.Checksums
import io.nativeforge.core.packages.install.InstalledPackageDatabase
import io.nativeforge.core.packages.model.ArchiveFormat
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.packages.model.InstallTarget
import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.source.PackageSource
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ToolchainLayout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [PackageManager.installed] is what the editor follows to notice that a project's libraries
 * arrived (and restart clangd with their headers), so it must change after every install.
 */
class PackageManagerInstalledTest {

    private val root: File = Files.createTempDirectory("nf-installed").toFile()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private val archives = mapOf(
        "https://example.invalid/liba.zip" to zip("include/a.h" to "int a(void);\n", "lib/liba.a" to "!<arch>\n"),
        "https://example.invalid/libb.zip" to zip("include/b.h" to "int b(void);\n"),
    )

    /** Serves the archives; libb's checksum in the index is wrong, so its install fails. */
    private val http = object : HttpClient {
        override suspend fun getBytes(url: String): ByteArray = throw IOException("not used")
        override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
            destination.parentFile?.mkdirs()
            destination.writeBytes(archives[url] ?: throw IOException("404 $url"))
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val file = File(root, "sum.tmp").apply { writeBytes(bytes) }
        return Checksums.sha256(file).also { file.delete() }
    }

    private fun library(name: String, sha256: String, depends: List<String> = emptyList()) = PackageInfo(
        name = name, version = "1", architecture = "aarch64", depends = depends,
        downloadUrl = "https://example.invalid/$name.zip", sha256 = sha256,
        format = ArchiveFormat.ZIP, target = InstallTarget.ISOLATED, sourceId = "bundled",
    )

    private val source = object : PackageSource {
        override val id = "bundled"
        override val displayName = "Bundled"
        override suspend fun fetchIndex(abi: Abi) = listOf(
            library("liba", sha256(archives.getValue("https://example.invalid/liba.zip"))),
            library("libb", "0".repeat(64), depends = listOf("liba")),
        )
    }

    private val manager = PackageManager(
        sources = listOf(source),
        http = http,
        layout = ToolchainLayout(root).also { it.androidLibrariesDir.mkdirs() },
        database = InstalledPackageDatabase(File(root, "installed.json")),
        abi = Abi.ARM64_V8A,
    )

    @Test
    fun `a library's headers become build inputs once it is installed`() = runBlocking {
        manager.refreshSource("bundled")
        assertEquals(emptyList(), manager.nativeDependencies(listOf("liba")))

        val events = manager.install(listOf("liba")).toList()
        assertIs<InstallEvent.Completed>(events.last())

        assertEquals(listOf("liba"), manager.installed.value.map { it.info.name })
        val dependency = manager.nativeDependencies(listOf("liba")).single()
        assertEquals(listOf(File(root, "android-libs/liba/include").canonicalPath), dependency.includeDirectories.map { it.canonicalPath })
        assertTrue(File(dependency.includeDirectories.single(), "a.h").isFile)
    }

    @Test
    fun `libraries installed before a failure are published`() = runBlocking {
        manager.refreshSource("bundled")
        val events = manager.install(listOf("libb")).toList()

        assertIs<InstallEvent.Failed>(events.last())
        assertTrue(events.any { it is InstallEvent.Installed && it.pkg.name == "liba" })
        // The dependency that did install is visible right away, without another install or a restart.
        assertEquals(listOf("liba"), manager.installed.value.map { it.info.name })
        assertEquals(listOf("liba"), manager.nativeDependencies(listOf("liba", "libb")).map { it.name })
    }
}
