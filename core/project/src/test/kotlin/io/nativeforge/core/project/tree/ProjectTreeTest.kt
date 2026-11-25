package io.nativeforge.core.project.tree

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectTreeTest {

    private val root: File = Files.createTempDirectory("nf-tree").toFile().apply {
        listOf("src/main.c", "src/game/player.c", "vendor/raylib/src/rcore.c", "build/debug/x.o", ".git/HEAD", "README.md")
            .forEach { File(this, it).apply { parentFile.mkdirs(); writeText("") } }
    }

    @Test
    fun `only open folders are read`() {
        val nodes = ProjectTree.list(root, expanded = setOf("src"))
        assertEquals(listOf("src", "src/game", "src/main.c", "vendor", "README.md"), nodes.map { it.relativePath })
        assertEquals(listOf(0, 1, 1, 0, 0), nodes.map { it.depth })
    }

    @Test
    fun `a folder inside a closed folder stays unread`() {
        val nodes = ProjectTree.list(root, expanded = setOf("vendor/raylib"))
        assertTrue(nodes.none { it.relativePath.startsWith("vendor/") })
    }

    @Test
    fun `listAll walks everything except build and hidden folders`() {
        val paths = ProjectTree.listAll(root).map { it.relativePath }
        assertTrue("vendor/raylib/src/rcore.c" in paths && "src/game/player.c" in paths)
        assertTrue(paths.none { it.startsWith("build") || it.startsWith(".git") })
    }

    @Test
    fun `listing stops at the node limit`() {
        assertEquals(3, ProjectTree.listAll(root, maxNodes = 3).size)
    }
}
