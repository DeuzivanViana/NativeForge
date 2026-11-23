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
}
