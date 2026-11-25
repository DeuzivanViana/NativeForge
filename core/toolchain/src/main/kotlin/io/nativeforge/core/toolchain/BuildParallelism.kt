package io.nativeforge.core.toolchain

import java.io.File

/**
 * How many compilers may run at once. Each clang can take several hundred MB on a phone,
 * so the job count follows the free memory as well as the cores: a phone with 8 cores and
 * 1.5 GB available builds with 3 jobs instead of swapping or getting killed.
 */
object BuildParallelism {

    /** Memory budget of one compile job. */
    const val MEMORY_PER_JOB_MB = 512L

    const val MAX_JOBS = 8

    fun jobs(
        cores: Int = Runtime.getRuntime().availableProcessors(),
        availableMemoryMb: Long? = availableMemoryMb(),
    ): Int {
        val byCpu = cores.coerceIn(1, MAX_JOBS)
        val byMemory = availableMemoryMb?.let { (it / MEMORY_PER_JOB_MB).toInt().coerceAtLeast(1) } ?: byCpu
        return minOf(byCpu, byMemory)
    }

    /** `MemAvailable` from `/proc/meminfo` in MB, or null when it cannot be read (not Linux). */
    fun availableMemoryMb(meminfo: File = File("/proc/meminfo")): Long? =
        runCatching { parseMemAvailableKb(meminfo.readText())?.div(1024) }.getOrNull()

    fun parseMemAvailableKb(text: String): Long? =
        text.lineSequence()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
}
