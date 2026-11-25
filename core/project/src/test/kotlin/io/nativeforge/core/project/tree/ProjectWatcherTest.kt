package io.nativeforge.core.project.tree

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectWatcherTest {

    private val root: File = Files.createTempDirectory("nf-watch").toFile().apply {
        File(this, "src").mkdirs()
        File(this, "build").mkdirs()
    }

    @Test
    fun `a new file in a source folder is reported`() = runBlocking {
        val change = async { withTimeout(10_000) { ProjectWatcher(root).changes(quietMillis = 50).first() } }
        delay(300) // let the watcher register its folders
        File(root, "src/new.c").writeText("")
        change.await()
    }

    @Test
    fun `files in folders created after the start are reported too`() = runBlocking {
        val change = async { withTimeout(10_000) { ProjectWatcher(root).changes(quietMillis = 50).first() } }
        delay(300)
        File(root, "src/game").mkdirs()
        change.await()
    }

    @Test
    fun `build output is ignored`() = runBlocking {
        val change = async { withTimeoutOrNull(1_500) { ProjectWatcher(root).changes(quietMillis = 50).first() } }
        delay(300)
        File(root, "build/main.o").writeText("")
        assertNull(change.await())
    }

    @Test
    fun `ignored paths`() {
        val base = root.toPath()
        assertTrue(ProjectWatcher.isIgnored(base, base.resolve("build/debug")))
        assertTrue(ProjectWatcher.isIgnored(base, base.resolve(".git/objects")))
        assertTrue(ProjectWatcher.isIgnored(base, base.resolve("src/.main.c.nf-save")))
        assertFalse(ProjectWatcher.isIgnored(base, base.resolve("src/build")))
        assertFalse(ProjectWatcher.isIgnored(base, base))
    }
}
