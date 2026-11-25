package io.nativeforge.core.common

import io.nativeforge.core.common.process.BackgroundThreads
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundThreadsTest {
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previous: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun catchUncaught() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @AfterTest
    fun restore() = Thread.setDefaultUncaughtExceptionHandler(previous)

    @Test
    fun `lines are read until the stream ends`() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        BackgroundThreads.drainLines("one\ntwo\n".byteInputStream(), "t") { lines += it }.join(5_000)
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `a stream closed under the reader ends the thread quietly`() {
        val failing = object : InputStream() {
            override fun read(): Int = throw IOException("Stream closed")
        }
        BackgroundThreads.drainLines(failing, "t").join(5_000)
        val brokenPipe = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
        }
        BackgroundThreads.writeAndClose(brokenPipe, ByteArray(10), "w").join(5_000)
        assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
    }

    @Test
    fun `destroying a process while its stderr is read does not throw on the reader`() {
        // What clangd's stderr reader went through when a project was closed (see ClangdSession).
        repeat(5) {
            val process = ProcessBuilder("sh", "-c", "while true; do echo noise >&2; done").start()
            val reader = BackgroundThreads.drainLines(process.errorStream, "stderr")
            Thread.sleep(50)
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            reader.join(5_000)
        }
        assertTrue(uncaught.isEmpty(), "uncaught: $uncaught")
    }

    @Test
    fun `errors from the block reach onError`() {
        val seen = mutableListOf<Exception>()
        BackgroundThreads.start("t", onError = { seen += it }) { error("boom") }.join(5_000)
        assertEquals("boom", seen.single().message)
    }
}
