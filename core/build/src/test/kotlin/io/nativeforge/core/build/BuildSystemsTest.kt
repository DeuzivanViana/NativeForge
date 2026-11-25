package io.nativeforge.core.build

import io.nativeforge.core.build.model.ArtifactKind
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.system.CMakeBuildSystem
import io.nativeforge.core.build.system.MakeBuildSystem
import io.nativeforge.core.build.system.NinjaBuildSystem
import io.nativeforge.core.project.model.CMakeGenerator
import io.nativeforge.core.build.system.IncrementalChecker
import io.nativeforge.core.build.system.SingleSourceBuildSystem
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.toolchain.NativeDependency
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuildSystemsTest {

    private val root: File = Files.createTempDirectory("nf-build").toFile()
    private val toolchain = FakeToolchain(File(root, "toolchain"))

    private fun project(config: ProjectConfig, vararg files: Pair<String, String>): Project {
        val dir = File(root, config.name)
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs(); writeText(content) } }
        return Project(dir, config)
    }

    @Test
    fun `single source console project compiles each file then links`() {
        val project = project(
            ProjectConfig(name = "hello", language = Language.CPP),
            "src/main.cpp" to "int main(){}",
            "src/util.c" to "int f(){return 1;}",
        )
        val plan = SingleSourceBuildSystem().plan(BuildRequest(project, toolchain))

        assertEquals(3, plan.steps.size)
        val util = plan.steps.first { it.description.contains("util.c") }.process.command
        assertEquals(toolchain.cCompiler.path, util.first())
        assertTrue("-std=c17" in util, "C files use a C standard even in C++ projects")

        val link = plan.steps.last().process.command
        assertEquals(toolchain.cxxCompiler.path, link.first())
        assertEquals(ArtifactKind.EXECUTABLE, plan.artifact.kind)
        assertEquals("hello", plan.artifact.file.name)
    }

    @Test
    fun `graphical runtime produces a position independent shared library`() {
        val sdlRoot = File(root, "sdl2")
        val project = project(
            ProjectConfig(name = "game", runtime = RuntimeKind.SDL2),
            "src/main.cpp" to "int main(){}",
        )
        val dependency = NativeDependency(
            name = "sdl2",
            includeDirectories = listOf(File(sdlRoot, "include/SDL2")),
            libraryDirectories = listOf(File(sdlRoot, "lib")),
            linkLibraries = listOf("SDL2"),
            runtimeLibraries = listOf(File(sdlRoot, "lib/libSDL2.so")),
        )
        val plan = SingleSourceBuildSystem().plan(BuildRequest(project, toolchain, dependencies = listOf(dependency)))

        val compile = plan.steps.first().process.command
        assertTrue("-fPIC" in compile)
        val system = compile.indexOf("${sdlRoot.path}/include/SDL2")
        assertTrue(system > 0 && compile[system - 1] == "-isystem", "library headers are system headers")
        val link = plan.steps.last().process.command
        assertTrue("-shared" in link && "-lSDL2" in link)
        // Missing symbols must fail the link, not the program's start-up.
        assertTrue("-Wl,--no-undefined" in link)
        assertTrue("-lm" in link)
        assertEquals("libmain.so", plan.artifact.file.name)
        assertEquals(listOf(File(sdlRoot, "lib/libSDL2.so")), plan.artifact.runtimeLibraries)
    }

    @Test
    fun `console programs are not linked with the shared library guards`() {
        val project = project(ProjectConfig(name = "cli", language = Language.C), "src/main.c" to "int main(){}")
        val link = SingleSourceBuildSystem().plan(BuildRequest(project, toolchain)).steps.last().process.command
        assertTrue("-Wl,--no-undefined" !in link && "-shared" !in link)
        assertTrue("-lm" in link)
    }

    @Test
    fun `unchanged sources are not recompiled`() {
        val project = project(ProjectConfig(name = "inc", language = Language.C), "src/main.c" to "int main(){}")
        val system = SingleSourceBuildSystem()
        val first = system.plan(BuildRequest(project, toolchain))
        assertEquals(2, first.steps.size)

        // Simulate clang producing outputs and a depfile.
        val objectFile = File(project.buildDirectory, "debug/obj/main.c.o").apply { writeText("obj") }
        File(project.buildDirectory, "debug/obj/main.c.d").writeText("${objectFile.path}: ${project.root}/src/main.c\n")
        first.artifact.file.writeText("elf")
        val past = System.currentTimeMillis() - 10_000
        File(project.root, "src/main.c").setLastModified(past)
        objectFile.setLastModified(past + 1000)

        assertEquals(0, system.plan(BuildRequest(project, toolchain)).steps.size)
    }

    @Test
    fun `depfile parsing handles continuations and escaped spaces`() {
        val deps = IncrementalChecker.parseDepFile("out.o: src/a.c \\\n  include/my\\ header.h /usr/b.h\n")
        assertEquals(listOf("src/a.c", "include/my header.h", "/usr/b.h"), deps)
    }

    @Test
    fun `cmake plan configures with ninja and passes package prefixes`() {
        val project = project(
            ProjectConfig(name = "cm", buildSystem = BuildSystemKind.CMAKE, runtime = RuntimeKind.NATIVE_ACTIVITY),
            "CMakeLists.txt" to "project(cm)",
        )
        val prefix = File(root, "raylib")
        val plan = CMakeBuildSystem().plan(
            BuildRequest(project, toolchain, dependencies = listOf(NativeDependency("raylib", cmakePrefixes = listOf(prefix)))),
        )
        val configure = plan.steps[0].process.command
        assertTrue(configure.containsAll(listOf("-G", "Ninja")))
        val cache = cmakeCache(plan)
        assertEquals(prefix.path, cache["CMAKE_PREFIX_PATH"])
        assertEquals("NATIVE_ACTIVITY", cache["NATIVEFORGE_RUNTIME"])
        // Projects that ignore the NATIVEFORGE_* variables must not get an "unused variables" warning:
        // they come from an initial-cache script, not -D, and no deprecated flag is needed.
        assertTrue(configure.none { it.startsWith("-D") || it == "--no-warn-unused-cli" }, configure.toString())
        assertEquals(listOf(toolchain.cmake.path, "--build"), plan.steps[1].process.command.take(2))
        assertEquals("libmain.so", plan.artifact.file.name)
    }

    @Test
    fun `cmake links package libraries into every target without editing CMakeLists`() {
        val project = project(
            ProjectConfig(name = "cm2", buildSystem = BuildSystemKind.CMAKE, runtime = RuntimeKind.NATIVE_ACTIVITY),
            "CMakeLists.txt" to "project(cm2)",
        )
        val dep = NativeDependency("raylib", includeDirectories = listOf(File(root, "rl/include")),
            libraryDirectories = listOf(File(root, "rl/lib")), linkLibraries = listOf("raylib", "EGL"))
        val cache = cmakeCache(CMakeBuildSystem().plan(BuildRequest(project, toolchain, dependencies = listOf(dep))))
        assertTrue("-isystem ${File(root, "rl/include").path}" in cache.getValue("CMAKE_CXX_FLAGS"))
        assertTrue(cache.getValue("CMAKE_CXX_STANDARD_LIBRARIES").contains("-lraylib -lEGL"))
        assertTrue(cache.getValue("CMAKE_SHARED_LINKER_FLAGS").contains("--no-undefined"))
        assertEquals("raylib;EGL", cache["NATIVEFORGE_LIBRARIES"])
    }

    @Test
    fun `cmake can use the makefile generator in its own build folder`() {
        File(toolchain.make.path).apply { parentFile.mkdirs(); writeText("") }
        val project = project(
            ProjectConfig(name = "cm3", buildSystem = BuildSystemKind.CMAKE, cmakeGenerator = CMakeGenerator.MAKEFILES),
            "CMakeLists.txt" to "project(cm3)",
        )
        val plan = CMakeBuildSystem().plan(BuildRequest(project, toolchain))
        val configure = plan.steps[0].process.command
        assertTrue(configure.containsAll(listOf("-G", "Unix Makefiles")))
        assertEquals(toolchain.make.path, cmakeCache(plan)["CMAKE_MAKE_PROGRAM"])
        assertTrue(plan.steps[1].process.command.containsAll(listOf("--", "SHELL=/system/bin/sh")))
        assertEquals("debug-makefiles", plan.artifact.file.parentFile.name)
    }

    @Test
    fun `make runs the project makefile with compiler variables`() {
        File(toolchain.make.path).apply { parentFile.mkdirs(); writeText("") }
        val project = project(
            ProjectConfig(name = "mk", buildSystem = BuildSystemKind.MAKE, buildTarget = "game", artifactPath = "bin/game"),
            "Makefile" to "all:\n",
        )
        val dep = NativeDependency("lua", libraryDirectories = listOf(File(root, "lua/lib")), linkLibraries = listOf("lua"))
        val plan = MakeBuildSystem().plan(BuildRequest(project, toolchain, dependencies = listOf(dep)))
        val step = plan.steps.single().process
        assertEquals(listOf(toolchain.make.path, "-C", project.root.path, "-j4", "SHELL=/system/bin/sh", "game"), step.command)
        // CC is the generated wrapper, first on PATH, which adds the target and the packages.
        val wrappers = File(project.root, "build/.nativeforge/bin")
        assertEquals(File(wrappers, "cc").path, step.environment.getValue("CC"))
        assertTrue(step.environment.getValue("PATH").startsWith(wrappers.path + ":"))
        val gxx = File(wrappers, "g++").readText()
        assertTrue(toolchain.cxxCompiler.path in gxx && "--target=aarch64-linux-android24" in gxx && "-llua" in gxx)
        assertTrue("-lX11" in gxx, "desktop-only libraries are dropped by the wrapper")
        assertTrue("-lua" !in step.environment.getValue("LDLIBS") && "-llua" in step.environment.getValue("LDLIBS"))
        assertTrue("-L${File(root, "lua/lib").path}" in step.environment.getValue("LDFLAGS"))
        assertEquals(File(project.root, "bin/game"), plan.artifact.file)
        assertEquals(project.root, plan.artifactSearchRoot)
    }

    @Test
    fun `ninja runs build ninja in the project folder`() {
        val project = project(ProjectConfig(name = "nj", buildSystem = BuildSystemKind.NINJA), "build.ninja" to "")
        val command = NinjaBuildSystem().plan(BuildRequest(project, toolchain)).steps.single().process.command
        assertEquals(listOf(toolchain.ninja.path, "-C", project.root.path, "-j", "4"), command)
    }

    @Test
    fun `sources in hidden and build folders are not compiled`() {
        val project = project(
            ProjectConfig(name = "rootsrc", sourceDir = "."),
            "main.c" to "int main(){}",
            "build/CMakeFiles/CompilerIdC/CMakeCCompilerId.c" to "",
            ".git/x.c" to "",
        )
        val plan = SingleSourceBuildSystem().plan(BuildRequest(project, toolchain))
        assertEquals(2, plan.steps.size)
    }

    @Test
    fun `program files and source folders outside the project are rejected`() {
        val project = project(ProjectConfig(name = "esc", buildSystem = BuildSystemKind.NINJA, artifactPath = "../../etc/passwd"), "build.ninja" to "")
        kotlin.test.assertFailsWith<IllegalArgumentException> { NinjaBuildSystem().plan(BuildRequest(project, toolchain)) }
        val single = project(ProjectConfig(name = "esc2", sourceDir = "../esc"), "src/main.c" to "int main(){}")
        kotlin.test.assertFailsWith<IllegalArgumentException> { SingleSourceBuildSystem().plan(BuildRequest(single, toolchain)) }
    }
}
