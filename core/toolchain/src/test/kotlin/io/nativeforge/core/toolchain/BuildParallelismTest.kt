package io.nativeforge.core.toolchain

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildParallelismTest {

    @Test
    fun `jobs follow the cores when memory is plentiful`() {
        assertEquals(4, BuildParallelism.jobs(cores = 4, availableMemoryMb = 8_000))
        assertEquals(BuildParallelism.MAX_JOBS, BuildParallelism.jobs(cores = 16, availableMemoryMb = 64_000))
    }

    @Test
    fun `free memory limits the jobs but never below one`() {
        assertEquals(3, BuildParallelism.jobs(cores = 8, availableMemoryMb = 1_600))
        assertEquals(1, BuildParallelism.jobs(cores = 8, availableMemoryMb = 100))
        assertEquals(8, BuildParallelism.jobs(cores = 8, availableMemoryMb = null), "unknown memory: cores only")
    }

    @Test
    fun `reads MemAvailable from meminfo`() {
        val text = "MemTotal:        7864320 kB\nMemFree:          204800 kB\nMemAvailable:    2097152 kB\n"
        assertEquals(2_097_152, BuildParallelism.parseMemAvailableKb(text))
        assertNull(BuildParallelism.parseMemAvailableKb("MemTotal: 1 kB\n"))
        val file = Files.createTempFile("meminfo", "").toFile().apply { writeText(text) }
        assertEquals(2_048, BuildParallelism.availableMemoryMb(file))
        assertNull(BuildParallelism.availableMemoryMb(File("/does/not/exist")))
    }
}
