package io.nativeforge.core.common

import io.nativeforge.core.common.crash.CrashKind
import io.nativeforge.core.common.crash.CrashReportStore
import io.nativeforge.core.common.crash.TombstoneDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportsTest {
    private val dir: File = Files.createTempDirectory("nf-crash").toFile()
    private val store = CrashReportStore(File(dir, "crashes"), keep = 3)

    @Test
    fun `an exception report has the details and the whole stack trace`() {
        val error = IllegalStateException("outer", java.io.IOException("Stream closed"))
        val file = store.write(CrashKind.JVM, 1_000, mapOf("App" to "0.11.2 (21)"), CrashReportStore.describe("clangd-stderr", error))
        val text = file.readText()
        assertTrue(text.startsWith("NativeForge crash report: Kotlin/Java exception"), text)
        assertTrue("App: 0.11.2 (21)" in text)
        assertTrue("Thread: clangd-stderr" in text)
        assertTrue("java.lang.IllegalStateException: outer" in text)
        assertTrue("Caused by: java.io.IOException: Stream closed" in text)
        assertTrue("CrashReportsTest" in text, "stack frames are included")
    }

    @Test
    fun `reports are listed newest first and only the newest are kept`() {
        listOf(10L, 40L, 20L, 30L).forEach { store.write(CrashKind.JVM, it, emptyMap(), "x") }
        assertEquals(listOf(40L, 30L, 20L), store.reports().map { it.time })
    }

    @Test
    fun `two reports at the same millisecond both survive`() {
        store.write(CrashKind.JVM, 5, emptyMap(), "a")
        store.write(CrashKind.NATIVE, 5, emptyMap(), "b")
        store.write(CrashKind.NATIVE, 5, emptyMap(), "c")
        assertEquals(3, store.reports().size)
    }

    @Test
    fun `unseen reports are the ones after markAllSeen`() {
        store.write(CrashKind.JVM, 100, emptyMap(), "old")
        store.markAllSeen()
        assertTrue(store.unseen().isEmpty())
        store.write(CrashKind.ANR, 200, emptyMap(), "new")
        assertEquals(listOf(CrashKind.ANR), store.unseen().map { it.kind })
    }

    @Test
    fun `the native handler's file becomes a report once`() {
        store.directory.mkdirs()
        store.nativePendingFile.writeText("signal 11 (SIGSEGV), code 1, fault addr 0x0\n#00 pc 0x1234 libfoo.so\n")
        val file = store.adoptNativeReport(mapOf("App" to "test"))!!
        assertTrue("signal 11 (SIGSEGV)" in file.readText())
        assertFalse(store.nativePendingFile.exists())
        assertNull(store.adoptNativeReport(emptyMap()))
        assertEquals(CrashKind.NATIVE, store.reports().single().kind)
    }

    @Test
    fun `reports near a time are found by kind`() {
        store.write(CrashKind.JVM, 50_000, emptyMap(), "x")
        assertTrue(store.hasReportNear(55_000, CrashKind.JVM))
        assertFalse(store.hasReportNear(55_000, CrashKind.NATIVE))
        assertFalse(store.hasReportNear(80_000, CrashKind.JVM))
    }

    @Test
    fun `clear removes everything and the exit check time persists`() {
        store.write(CrashKind.JVM, 1, emptyMap(), "x")
        store.exitReasonsCheckedAt = 1234
        store.clear()
        assertTrue(store.reports().isEmpty())
        assertEquals(1234, CrashReportStore(store.directory).exitReasonsCheckedAt)
    }

    @Test
    fun `a tombstone decodes to signal, backtrace and log lines`() {
        val frame0 = message {
            varint(1, 0x1a2b)
            string(4, "crash_here")
            varint(5, 12)
            string(6, "/data/app/lib/arm64/libnativeforge_shim.so")
            string(8, "abc123")
        }
        val frame1 = message { varint(1, 0x40); string(6, "/system/lib64/libc.so") }
        val thread = message {
            varint(1, 4321)
            string(2, "clangd-stderr")
            bytes(4, frame0)
            bytes(4, frame1)
        }
        val other = message { varint(1, 1); string(2, "main"); bytes(4, frame1) }
        val signal = message {
            varint(1, 11)
            string(2, "SIGSEGV")
            varint(3, 1)
            string(4, "SEGV_MAPERR")
            varint(8, 1)
            varint(9, 0x10)
        }
        val log = message { string(1, "11-25 13:00:00.000"); varint(2, 1); varint(3, 4321); varint(4, 6); string(5, "nf"); string(6, "about to crash") }
        val tombstone = message {
            varint(1, 1)
            string(4, "2026-11-25 13:00:00")
            varint(5, 1000)
            varint(6, 4321)
            string(9, "io.github.deuzivanviana.nativeforge")
            bytes(10, signal)
            string(14, "")
            bytes(15, message { string(1, "null pointer dereference") })
            bytes(16, message { varint(1, 4321); bytes(2, thread) })
            bytes(16, message { varint(1, 1); bytes(2, other) })
            bytes(18, message { string(1, "main"); bytes(2, log) })
        }

        val text = TombstoneDecoder.decode(tombstone)
        assertTrue("pid: 1000, tid: 4321, name: clangd-stderr  >>> io.github.deuzivanviana.nativeforge <<<" in text, text)
        assertTrue("signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x10" in text, text)
        assertTrue("Cause: null pointer dereference" in text, text)
        assertTrue("#00 pc 0000000000001a2b  /data/app/lib/arm64/libnativeforge_shim.so (crash_here+12) (BuildId: abc123)" in text, text)
        assertTrue("#01 pc 0000000000000040  /system/lib64/libc.so" in text, text)
        assertTrue("Other threads: 1 main" in text, text)
        assertTrue("4321 E nf: about to crash" in text, text)
    }

    @Test
    fun `data that is not a tombstone falls back to its readable strings`() {
        val junk = byteArrayOf(0x7f, 0x01) + "libsomething.so".toByteArray() + byteArrayOf(0x00, 0x03)
        val text = TombstoneDecoder.decode(junk)
        assertTrue(text.startsWith("Tombstone (not decoded"), text)
        assertTrue("libsomething.so" in text)
    }

    /** A tiny protobuf writer for building test tombstones. */
    private class Message {
        val out = ByteArrayOutputStream()
        fun rawVarint(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7fL.inv() == 0L) { out.write(v.toInt()); return }
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
        }
        fun varint(field: Int, value: Number) { rawVarint((field shl 3).toLong()); rawVarint(value.toLong()) }
        fun bytes(field: Int, value: ByteArray) { rawVarint(((field shl 3) or 2).toLong()); rawVarint(value.size.toLong()); out.write(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
    }

    private fun message(block: Message.() -> Unit): ByteArray = Message().apply(block).out.toByteArray()
}
