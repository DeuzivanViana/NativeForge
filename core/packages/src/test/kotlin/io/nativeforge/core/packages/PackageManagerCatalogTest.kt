package io.nativeforge.core.packages

import io.nativeforge.core.packages.install.InstalledPackageDatabase
import io.nativeforge.core.packages.model.ArchiveFormat
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.packages.model.InstallTarget
import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.packages.net.HttpClient
import io.nativeforge.core.packages.source.PackageSource
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.ToolchainLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The catalog can hold only the bundled index; installing must still read every source. */
class PackageManagerCatalogTest {

    private val root: File = Files.createTempDirectory("nf-pm").toFile()

    private class FakeSource(override val id: String, private val names: List<String>) : PackageSource {
        override val displayName = id
        var fail = false
        var fetches = 0
        override suspend fun fetchIndex(abi: Abi): List<PackageInfo> {
            fetches++
            if (fail) throw IOException("Unable to resolve host")
            return names.map {
                PackageInfo(
                    name = it, version = "1", architecture = "aarch64", downloadUrl = "https://example.invalid/$it",
                    format = ArchiveFormat.DEB, target = InstallTarget.PREFIX, sourceId = id,
                )
            }
        }
    }

    private val noNetwork = object : HttpClient {
        override suspend fun getBytes(url: String): ByteArray = throw IOException("offline")
        override suspend fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) = throw IOException("offline")
    }

    private val bundled = FakeSource("bundled", listOf("raylib"))
    private val termux = FakeSource("termux", listOf("clang", "make"))
    private val manager = PackageManager(
        sources = listOf(bundled, termux),
        http = noNetwork,
        layout = ToolchainLayout(root),
        database = InstalledPackageDatabase(File(root, "installed.json")),
        abi = Abi.ARM64_V8A,
    )

    private fun firstOutcome(vararg names: String) = runBlocking {
        manager.install(names.toList()).first { it is InstallEvent.Resolved || it is InstallEvent.Failed }
    }

    @Test
    fun `toolchain packages resolve after only the bundled index was read at start-up`() = runBlocking {
        manager.refreshSource("bundled")
        assertEquals(listOf("raylib"), manager.catalog.value.map { it.name })
        assertTrue(!manager.allSourcesLoaded)

        val event = firstOutcome("clang", "make")
        assertIs<InstallEvent.Resolved>(event)
        assertEquals(listOf("clang", "make"), event.packages.map { it.name })
        assertTrue(manager.allSourcesLoaded)
    }

    @Test
    fun `a package list that cannot be downloaded is reported as a network problem`() = runBlocking {
        manager.refreshSource("bundled")
        termux.fail = true
        val event = firstOutcome("clang")
        assertIs<InstallEvent.Failed>(event)
        assertTrue("internet" in event.error.message && "Unable to resolve host" in event.error.message, event.error.message)
    }

    @Test
    fun `bundled libraries install without waiting for the other package lists`() = runBlocking {
        manager.refreshSource("bundled")
        termux.fail = true
        val events = manager.install(listOf("raylib")).toList()
        // Planned from the bundled index alone: Termux was never asked, and nothing waited on it.
        val resolved = events.filterIsInstance<InstallEvent.Resolved>().single()
        assertEquals(listOf("raylib"), resolved.packages.map { it.name })
        assertEquals(0, termux.fetches)
        assertTrue(events.none { it is InstallEvent.Preparing }, events.toString())
    }

    @Test
    fun `reading the package lists is announced before the plan`() = runBlocking {
        manager.refreshSource("bundled")
        val events = manager.install(listOf("clang")).toList()
        val preparing = events.indexOfFirst { it is InstallEvent.Preparing }
        val resolved = events.indexOfFirst { it is InstallEvent.Resolved }
        assertTrue(preparing in 0 until resolved, events.toString())
        assertEquals(1, termux.fetches)
    }
}
