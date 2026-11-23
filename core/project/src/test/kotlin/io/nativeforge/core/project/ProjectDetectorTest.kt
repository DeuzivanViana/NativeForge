package io.nativeforge.core.project

import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.RuntimeKind
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProjectDetectorTest {

    private val root: File = Files.createTempDirectory("nf-detect").toFile()

    private fun folder(name: String, vararg files: Pair<String, String>): File =
        File(root, name).also { dir -> files.forEach { (p, c) -> File(dir, p).apply { parentFile.mkdirs(); writeText(c) } } }

    @Test
    fun `cmake raylib game is detected as a graphical C++ project`() {
        val dir = folder(
            "game",
            "CMakeLists.txt" to "project(game)",
            "src/main.cpp" to "#include \"raylib.h\"\n#include <box2d/box2d.h>\nint main() {}",
        )
        val config = ProjectDetector.detect(dir, "game")
        assertEquals(BuildSystemKind.CMAKE, config.buildSystem)
        assertEquals(Language.CPP, config.language)
        assertEquals(RuntimeKind.NATIVE_ACTIVITY, config.runtime)
        assertEquals(listOf("raylib", "box2d"), config.packages)
        assertEquals("", config.cmakeTarget)
    }

    @Test
    fun `makefile C tool with sources in the root runs in the terminal`() {
        val dir = folder("tool", "Makefile" to "all:\n", "main.c" to "#include <stdio.h>\nint main(void) { return 0; }")
        val config = ProjectDetector.detect(dir, "tool")
        assertEquals(BuildSystemKind.MAKE, config.buildSystem)
        assertEquals(Language.C, config.language)
        assertEquals(RuntimeKind.CONSOLE, config.runtime)
        assertEquals(".", config.sourceDir)
    }

    @Test
    fun `sdl3 sources use the SDL runtime and import keeps the folder`() = runBlocking<Unit> {
        val workspace = File(root, "ws").apply { mkdirs() }
        val repository = FileSystemProjectRepository(workspace)
        val dir = (repository.directoryFor("sdl-demo") as Outcome.Success).value
        File(dir, "src").mkdirs()
        File(dir, "src/app.c").writeText("#include <SDL3/SDL.h>\n#include <SDL3_image/SDL_image.h>\n")
        val project = (repository.importDirectory(dir) as Outcome.Success).value
        assertEquals(RuntimeKind.SDL3, project.config.runtime)
        assertEquals(listOf("sdl3-image", "sdl3"), project.config.packages)
        assertEquals(listOf("sdl-demo"), repository.listProjects().map { it.name })
        assertIs<Outcome.Failure>(repository.directoryFor("sdl-demo"))
        assertIs<Outcome.Failure>(repository.importDirectory(File(root, "elsewhere").apply { mkdirs() }))
    }
}
