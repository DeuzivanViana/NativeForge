package io.nativeforge.core.build

import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.system.IncrementalChecker
import io.nativeforge.core.build.system.SingleSourceBuildSystem
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long the built-in build takes to decide what to compile, for a project of [SOURCES]
 * files: from scratch, and when everything is up to date (the depfile and command checks of
 * every object). Runs only with NF_BENCHMARK=1 (the Benchmarks workflow); writes its numbers to
 * `build/benchmark/build-plan.txt` and fails only on a gross regression.
 */
class BuildPlanBenchmark {

    @Test
    fun `plan a large project`() {
        assumeTrue(System.getenv("NF_BENCHMARK") == "1")
        val root = Files.createTempDirectory("nf-bench").toFile()
        val toolchain = FakeToolchain(File(root, "toolchain"))
        val headers = (0 until 20).map { File(root, "p/include/h$it.h").apply { parentFile.mkdirs(); writeText("") } }
        val project = Project(File(root, "p"), ProjectConfig(name = "p"))
        repeat(SOURCES) { File(project.sourceDirectory, "dir${it % 10}/s$it.cpp").apply { parentFile.mkdirs(); writeText("") } }
        val request = BuildRequest(project, toolchain)

        val checker = IncrementalChecker()
        lateinit var plan: io.nativeforge.core.build.model.BuildPlan
        val cold = measureNanoTime { plan = SingleSourceBuildSystem(checker).plan(request) } / 1_000_000
        assertEquals(SOURCES + 1, plan.steps.size)

        // Pretend the compiles ran: objects and depfiles listing the headers, newer than the sources.
        val later = System.currentTimeMillis() + 10_000
        plan.steps.dropLast(1).forEach { step ->
            val command = step.process.command
            val obj = File(command[command.indexOf("-o") + 1])
            val dep = File(command[command.indexOf("-MF") + 1])
            obj.writeText("")
            dep.writeText(obj.path + ": " + command[command.indexOf("-c") + 1] + " " + headers.joinToString(" ") { it.path } + "\n")
            obj.setLastModified(later)
        }
        val artifact = plan.artifact.file.apply { writeText("") ; setLastModified(later + 1_000) }
        val warm = (1..5).minOf { measureNanoTime { plan = SingleSourceBuildSystem(checker).plan(request) } } / 1_000_000
        assertTrue(plan.steps.isEmpty(), "everything is up to date")
        assertTrue(artifact.isFile)

        val report = "| Sources | From scratch | Up to date |\n| ---: | ---: | ---: |\n| $SOURCES | $cold ms | $warm ms |\n"
        File("build/benchmark").apply { mkdirs() }.resolve("build-plan.txt").writeText(report)
        println(report)
        assertTrue(warm < 10_000, "an up-to-date check of $SOURCES files took $warm ms")
    }

    private companion object {
        const val SOURCES = 600
    }
}
