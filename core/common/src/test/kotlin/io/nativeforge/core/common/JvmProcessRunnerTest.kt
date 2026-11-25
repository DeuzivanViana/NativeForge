package io.nativeforge.core.common

import io.nativeforge.core.common.process.JvmProcessRunner
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.common.process.ProcessEvent
import io.nativeforge.core.common.process.ProcessSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmProcessRunnerTest {

    @Test
    fun `captures stdout, stderr and exit code`() = runBlocking<Unit> {
        val spec = ProcessSpec(
            command = listOf("sh", "-c", "echo hello; echo oops 1>&2; exit 3"),
            workingDirectory = File("."),
        )
        val events = JvmProcessRunner().run(spec).toList()

        assertTrue(ProcessEvent.Output(OutputStream.STDOUT, "hello") in events)
        assertTrue(ProcessEvent.Output(OutputStream.STDERR, "oops") in events)
        assertEquals(ProcessEvent.Exited(3), events.last())
    }

    @Test
    fun `command line quotes arguments with spaces`() {
        val spec = ProcessSpec(listOf("clang", "-o", "my app", "main.c"), File("."))
        assertEquals("clang -o \"my app\" main.c", spec.commandLine())
    }

    @Test
    fun `a lower start priority still runs the process and leaves the caller's priority alone`() = runBlocking<Unit> {
        val before = Thread.currentThread().priority
        var asked = 0
        val runner = JvmProcessRunner(priority = { asked++; Thread.MIN_PRIORITY })
        val events = runner.run(ProcessSpec(listOf("sh", "-c", "echo low"), File("."))).toList()
        assertEquals(1, asked)
        assertTrue(ProcessEvent.Output(OutputStream.STDOUT, "low") in events)
        assertEquals(ProcessEvent.Exited(0), events.last())
        assertEquals(before, Thread.currentThread().priority)
    }

    @Test
    fun `a missing program fails the flow at a lower priority too`() = runBlocking<Unit> {
        val runner = JvmProcessRunner(priority = { Thread.MIN_PRIORITY })
        val result = runCatching { runner.run(ProcessSpec(listOf("/nonexistent/nf-tool"), File("."))).toList() }
        assertTrue(result.exceptionOrNull() is java.io.IOException, "${result.exceptionOrNull()}")
    }

    @Test
    fun `hotter phones start builds at lower priorities`() {
        assertEquals(ThermalLevel.NONE, ThermalLevel.fromAndroidStatus(0))
        assertEquals(ThermalLevel.MODERATE, ThermalLevel.fromAndroidStatus(2))
        assertEquals(ThermalLevel.CRITICAL, ThermalLevel.fromAndroidStatus(6))
        assertEquals(Thread.NORM_PRIORITY, ThermalLevel.LIGHT.buildThreadPriority)
        assertTrue(ThermalLevel.MODERATE.buildThreadPriority < Thread.NORM_PRIORITY)
        assertEquals(Thread.MIN_PRIORITY, ThermalLevel.SEVERE.buildThreadPriority)
    }
}
