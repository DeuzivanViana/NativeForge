package io.nativeforge.core.build

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.system.CMakeBuildSystem
import io.nativeforge.core.build.system.IncrementalChecker
import io.nativeforge.core.build.system.MakeBuildSystem
import io.nativeforge.core.build.system.SingleSourceBuildSystem
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.BuildType
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ccache, precompiled headers, split DWARF, ThinLTO and release flags. */
class BuildSpeedTest {

    private val root: File = Files.createTempDirectory("nf-speed").toFile()
    private val ccache = File(root, "toolchain/bin/ccache")
    private val plain = FakeToolchain(File(root, "toolchain"))
    private val fast = FakeToolchain(File(root, "toolchain"), compilerCache = ccache, supportsSplitDwarf = true, supportsThinLto = true)

    private fun project(config: ProjectConfig, vararg files: Pair<String, String>): Project {
        val dir = File(root, config.name)
        files.forEach { (path, content) -> File(dir, path).apply { parentFile.mkdirs(); writeText(content) } }
        return Project(dir, config)
    }

    @Test
    fun `compiles go through ccache when installed, links never do`() {
        val project = project(ProjectConfig(name = "cached"), "src/main.cpp" to "int main(){}")
        val steps = SingleSourceBuildSystem().plan(BuildRequest(project, fast)).steps
        assertEquals(listOf(ccache.path, fast.cxxCompiler.path), steps.first().process.command.take(2))
        assertEquals(fast.cxxCompiler.path, steps.last().process.command.first())

        val off = project(ProjectConfig(name = "uncached", compilerCache = false), "src/main.cpp" to "int main(){}")
        val compile = SingleSourceBuildSystem().plan(BuildRequest(off, fast)).steps.first().process.command
        assertEquals(fast.cxxCompiler.path, compile.first(), "the project can turn ccache off")

        val none = SingleSourceBuildSystem().plan(BuildRequest(project(ProjectConfig(name = "nocache"), "src/main.c" to ""), plain))
        assertTrue(none.steps.none { ccache.path in it.process.command })
    }

    @Test
    fun `cmake and makefile builds use ccache as the compiler launcher`() {
        val cmake = project(ProjectConfig(name = "cm", buildSystem = BuildSystemKind.CMAKE), "CMakeLists.txt" to "")
        val configure = CMakeBuildSystem().plan(BuildRequest(cmake, fast)).steps[0].process.command
        assertTrue("-DCMAKE_C_COMPILER_LAUNCHER=${ccache.path}" in configure)
        assertTrue("-DCMAKE_CXX_COMPILER_LAUNCHER=${ccache.path}" in configure)

        val make = project(ProjectConfig(name = "mk", buildSystem = BuildSystemKind.MAKE), "Makefile" to "all:\n")
        fast.make.apply { parentFile.mkdirs(); writeText("") }
        MakeBuildSystem().plan(BuildRequest(make, fast))
        val lines = File(make.root, "build/.nativeforge/bin/gcc").readLines()
        val link = lines.single { it.trimStart().startsWith("exec") && "\"\$@\" " in it }
        val compileOnly = lines.last { it.startsWith("exec") }
        assertTrue(compileOnly.startsWith("exec ${ccache.path} "), compileOnly)
        assertFalse(ccache.path in link, "links run clang directly")
    }

    @Test
    fun `debug builds split the dwarf data when the toolchain can`() {
        val project = project(ProjectConfig(name = "dbg"), "src/main.c" to "")
        assertTrue("-gsplit-dwarf" in SingleSourceBuildSystem().plan(BuildRequest(project, fast)).steps.first().process.command)
        assertFalse("-gsplit-dwarf" in SingleSourceBuildSystem().plan(BuildRequest(project, plain)).steps.first().process.command)
    }

    @Test
    fun `release builds drop unused sections, strip and use ThinLTO with lld`() {
        val project = project(ProjectConfig(name = "rel", runtime = RuntimeKind.SDL3), "src/main.cpp" to "")
        val steps = SingleSourceBuildSystem().plan(BuildRequest(project, fast, BuildType.RELEASE)).steps
        val compile = steps.first().process.command
        assertTrue(listOf("-O2", "-DNDEBUG", "-ffunction-sections", "-fdata-sections", "-flto=thin").all { it in compile })
        assertFalse("-gsplit-dwarf" in compile)
        val link = steps.last().process.command
        assertTrue(listOf("-Wl,--gc-sections", "-s", "-flto=thin").all { it in link })
        assertTrue(link.any { it.startsWith("-Wl,--thinlto-cache-dir=") })

        val noLld = SingleSourceBuildSystem().plan(BuildRequest(project, plain, BuildType.RELEASE)).steps
        assertTrue(noLld.none { "-flto=thin" in it.process.command }, "no ThinLTO without lld")
        assertTrue("-s" in noLld.last().process.command)
    }

    @Test
    fun `cmake release builds get the section and link flags`() {
        val project = project(ProjectConfig(name = "cmrel", buildSystem = BuildSystemKind.CMAKE), "CMakeLists.txt" to "")
        val configure = CMakeBuildSystem().plan(BuildRequest(project, plain, BuildType.RELEASE)).steps[0].process.command
        assertTrue(configure.single { it.startsWith("-DCMAKE_CXX_FLAGS=") }.contains("-ffunction-sections"))
        assertTrue(configure.single { it.startsWith("-DCMAKE_EXE_LINKER_FLAGS=") }.contains("-Wl,--gc-sections"))
    }

    @Test
    fun `precompiled header is built first and loaded by every C and C++ source`() {
        val project = project(
            ProjectConfig(name = "pch", language = Language.CPP, precompiledHeader = "src/pch.h"),
            "src/pch.h" to "#include <vector>",
            "src/main.cpp" to "int main(){}",
            "src/util.c" to "int f(){return 1;}",
            "src/start.S" to "",
        )
        val steps = SingleSourceBuildSystem().plan(BuildRequest(project, fast)).steps
        val precompile = steps.take(2)
        assertTrue(precompile.all { it.description.startsWith("Precompiling pch.h") && !it.parallel })
        val cxxPch = precompile.single { "c++-header" in it.process.command }.process.command.let { it[it.indexOf("-o") + 1] }
        val cPch = precompile.single { "c-header" in it.process.command }.process.command.let { it[it.indexOf("-o") + 1] }

        fun compileOf(name: String) = steps.single { it.description == "Compiling $name" }.process.command
        assertEquals(cxxPch, compileOf("main.cpp").let { it[it.indexOf("-include-pch") + 1] })
        assertEquals(cPch, compileOf("util.c").let { it[it.indexOf("-include-pch") + 1] })
        assertFalse("-include-pch" in compileOf("start.S"), "assembly does not load the header")
        assertTrue(steps.last().description.startsWith("Linking"))
    }

    @Test
    fun `a missing or outside precompiled header fails the plan`() {
        val missing = project(ProjectConfig(name = "nopch", precompiledHeader = "src/none.h"), "src/main.c" to "")
        assertFailsWith<IllegalArgumentException> { SingleSourceBuildSystem().plan(BuildRequest(missing, plain)) }
        val outside = project(ProjectConfig(name = "outpch", precompiledHeader = "../pch/src/pch.h"), "src/main.c" to "")
        assertFailsWith<IllegalArgumentException> { SingleSourceBuildSystem().plan(BuildRequest(outside, plain)) }
    }

    @Test
    fun `compile database includes the precompiled header for clangd`() {
        val project = project(
            ProjectConfig(name = "pchdb", precompiledHeader = "include/all.h"),
            "include/all.h" to "",
            "src/main.cpp" to "",
        )
        val args = CompileDatabase.commands(BuildRequest(project, plain)).single().arguments
        assertEquals(File(project.root, "include/all.h").path, args[args.indexOf("-include") + 1])
    }

    @Test
    fun `an object older than an extra input such as the pch is rebuilt`() {
        val dir = File(root, "inc").apply { mkdirs() }
        val source = File(dir, "a.c").apply { writeText("") }
        val obj = File(dir, "a.o")
        val dep = File(dir, "a.d").apply { writeText("a.o: ${source.path}\n") }
        val pch = File(dir, "p.pch").apply { writeText("") }
        val checker = IncrementalChecker()
        val command = listOf("clang", "-c", source.path)
        checker.recordPending(obj, command)
        obj.writeText("")
        source.setLastModified(1_000_000)
        pch.setLastModified(1_000_000)
        obj.setLastModified(2_000_000)
        assertTrue(checker.isObjectUpToDate(source, obj, dep, command, listOf(pch)))
        pch.setLastModified(3_000_000)
        assertFalse(checker.isObjectUpToDate(source, obj, dep, command, listOf(pch)))
    }
}
