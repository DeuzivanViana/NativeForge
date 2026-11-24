package io.nativeforge.core.build

import io.nativeforge.core.build.model.ArtifactKind
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildPlan
import io.nativeforge.core.build.model.BuildStep
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.common.process.ProcessEvent
import io.nativeforge.core.common.process.ProcessRunner
import io.nativeforge.core.common.process.ProcessSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildExecutorTest {

    private val root: File = Files.createTempDirectory("nf-exec").toFile()
    private val artifact = File(root, "program").apply { writeText("") }

    /** Every command is `name [exit code]`; it prints two lines, takes 100 ms and records how many run at once. */
    private class FakeRunner : ProcessRunner {
        val running = AtomicInteger()
        val mostAtOnce = AtomicInteger()
        val started = mutableListOf<String>()

        override fun run(spec: ProcessSpec) = flow {
            val name = spec.command[0]
            synchronized(started) { started += name }
            mostAtOnce.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            emit(ProcessEvent.Output(OutputStream.STDERR, "$name: first line"))
            delay(100)
            emit(ProcessEvent.Output(OutputStream.STDERR, "$name: second line"))
            running.decrementAndGet()
            emit(ProcessEvent.Exited(spec.command.getOrNull(1)?.toInt() ?: 0))
        }
    }

    private fun step(name: String, parallel: Boolean, exitCode: Int = 0) =
        BuildStep(name, ProcessSpec(listOf(name, exitCode.toString()), root), parallel)

    private fun plan(steps: List<BuildStep>, jobs: Int) =
        BuildPlan(steps, BuildArtifact(artifact, ArtifactKind.EXECUTABLE, emptyList()), parallelJobs = jobs)

    @Test
    fun `compile steps run together and the link waits for them`() = runTest {
        val runner = FakeRunner()
        val steps = (1..5).map { step("cc$it", parallel = true) } + step("link", parallel = false)
        val events = BuildExecutor(runner).execute(plan(steps, jobs = 3)).toList()

        assertEquals(3, runner.mostAtOnce.get(), "no more than parallelJobs at once")
        assertEquals("link", runner.started.last())
        val result = (events.last() as BuildEvent.Finished).result
        assertTrue(result.success)
        // Each step's lines stay together, right after its "started" event.
        val lines = events.filterIsInstance<BuildEvent.Output>().map { it.line }
        for (name in steps.map { it.description }) {
            val first = lines.indexOf("$name: first line")
            assertEquals("$name: second line", lines[first + 1])
        }
        val indices = events.filterIsInstance<BuildEvent.StepStarted>().map { it.index }.sorted()
        assertEquals((0..5).toList(), indices)
    }

    @Test
    fun `a failed compile stops the build before the link`() = runTest {
        val runner = FakeRunner()
        val steps = listOf(step("cc1", true), step("cc2", true, exitCode = 1), step("cc3", true), step("cc4", true)) +
            step("link", parallel = false)
        val events = BuildExecutor(runner).execute(plan(steps, jobs = 2)).toList()

        assertFalse("link" in runner.started)
        assertFalse("cc3" in runner.started || "cc4" in runner.started, "nothing new starts after a failure")
        assertFalse((events.last() as BuildEvent.Finished).result.success)
        assertTrue(events.any { it is BuildEvent.StepFinished && it.exitCode == 1 })
    }

    @Test
    fun `one job runs the steps one after another`() = runTest {
        val runner = FakeRunner()
        val steps = (1..3).map { step("cc$it", parallel = true) }
        BuildExecutor(runner).execute(plan(steps, jobs = 1)).toList()
        assertEquals(1, runner.mostAtOnce.get())
        assertEquals(listOf("cc1", "cc2", "cc3"), runner.started)
    }
}
