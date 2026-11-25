package io.nativeforge.core.build

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.system.CMakeBuildSystem
import io.nativeforge.core.build.system.CMakeInitialCache
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CMakeInitialCacheTest {
    private val root = Files.createTempDirectory("nf-cmake").toFile()
    private val toolchain = FakeToolchain(File(root, "toolchain"))

    @Test
    fun `values survive quotes, dollars, backslashes, semicolons and brackets`() {
        val values = linkedMapOf(
            "CMAKE_C_FLAGS" to """-DNAME="a b" -DPATH=C:\dir -DREF=${'$'}{HOME}""",
            "NATIVEFORGE_LIBRARIES" to "raylib;EGL;m",
            "TRICKY" to "x]]y]=]z",
            "EMPTY" to "",
        )
        val script = CMakeInitialCache.render(values)
        assertEquals(values, CMakeInitialCache.parse(script))
        assertTrue("""set(TRICKY [==[x]]y]=]z]==] CACHE STRING "Set by NativeForge" FORCE)""" in script, script)
    }

    @Test
    fun `entries are forced and typed like CMake caches them`() {
        val script = CMakeInitialCache.render(
            linkedMapOf("CMAKE_C_COMPILER" to "/bin/clang", "NATIVEFORGE" to "ON", "CMAKE_BUILD_TYPE" to "Debug"),
        )
        assertTrue("""set(CMAKE_C_COMPILER [[/bin/clang]] CACHE FILEPATH "Set by NativeForge" FORCE)""" in script)
        assertTrue("""set(NATIVEFORGE [[ON]] CACHE BOOL "Set by NativeForge" FORCE)""" in script)
        assertTrue("""set(CMAKE_BUILD_TYPE [[Debug]] CACHE STRING "Set by NativeForge" FORCE)""" in script)
    }

    @Test
    fun `configure loads the script before the project's own -D options`() {
        val dir = File(root, "p").apply { mkdirs() }
        File(dir, "CMakeLists.txt").writeText("project(p)")
        val config = ProjectConfig(
            name = "p", buildSystem = BuildSystemKind.CMAKE,
            cmakeOptions = linkedMapOf("BUILD_TESTING" to "OFF", "CMAKE_BUILD_TYPE" to "RelWithDebInfo"),
        )
        val plan = CMakeBuildSystem().plan(BuildRequest(Project(dir, config), toolchain))
        val configure = plan.steps[0].process.command
        val script = File(configure[configure.indexOf("-C") + 1])
        assertEquals(CMakeBuildSystem.INITIAL_CACHE, script.name)
        assertEquals(plan.artifactSearchRoot, script.parentFile)
        assertEquals(listOf("-DBUILD_TESTING=OFF", "-DCMAKE_BUILD_TYPE=RelWithDebInfo"), configure.filter { it.startsWith("-D") })
        assertTrue(configure.indexOf("-C") < configure.indexOf("-DBUILD_TESTING=OFF"), "later options override the script")
        assertEquals("Debug", cmakeCache(plan)["CMAKE_BUILD_TYPE"])
        assertEquals("ON", cmakeCache(plan)["CMAKE_EXPORT_COMPILE_COMMANDS"])

        // An unchanged script is not rewritten, so CMake does not see a newer file on every build.
        val stamp = script.lastModified() - 5_000
        script.setLastModified(stamp)
        CMakeBuildSystem().plan(BuildRequest(Project(dir, config), toolchain))
        assertEquals(stamp, script.lastModified())
    }
}
