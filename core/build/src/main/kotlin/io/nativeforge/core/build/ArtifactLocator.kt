package io.nativeforge.core.build

import io.nativeforge.core.build.model.ArtifactKind
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Finds the program a third-party build script produced when its name is not configured. */
internal object ArtifactLocator {

    private const val MAX_DEPTH = 5
    private val SKIPPED_DIRS = setOf(".git", "CMakeFiles", "obj", ".cache", ".nativeforge")

    /**
     * The newest ELF program of [kind] below [root], preferring files written at or after
     * [sinceMillis]. When the build wrote nothing ("make: Nothing to be done"), the newest
     * existing program is returned, so an up-to-date project still runs.
     * The ELF header decides the kind, not the name: `app.elf` can be a shared library.
     */
    fun newest(root: File, kind: ArtifactKind, sinceMillis: Long): File? {
        val candidates = root.walkTopDown()
            .maxDepth(MAX_DEPTH)
            .onEnter { it == root || (it.name !in SKIPPED_DIRS && !Files.isSymbolicLink(it.toPath())) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && matches(it, kind) }
            .toList()
        val fresh = candidates.filter { it.lastModified() >= sinceMillis - CLOCK_SLACK_MILLIS }
        return (fresh.ifEmpty { candidates }).maxByOrNull { it.lastModified() }
    }

    private fun matches(file: File, kind: ArtifactKind): Boolean {
        val name = file.name
        if (name.endsWith(".o") || name.endsWith(".a") || name.endsWith(".ko")) return false
        return ElfInfo.kindOf(file) == kind
    }

    /** File systems store modification times with coarse precision. */
    private const val CLOCK_SLACK_MILLIS = 2_000L
}

/** Minimal ELF header reader: tells shared libraries from executables. */
internal object ElfInfo {
    private const val ET_EXEC = 2
    private const val ET_DYN = 3
    private const val PT_INTERP = 3

    fun kindOf(file: File): ArtifactKind? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 64) return null
            val header = ByteArray(64)
            raf.readFully(header)
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) {
                return null
            }
            val is64 = header[4].toInt() == 2
            val order = if (header[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            val buffer = ByteBuffer.wrap(header).order(order)
            when (buffer.getShort(16).toInt() and 0xFFFF) {
                ET_EXEC -> ArtifactKind.EXECUTABLE
                // Position-independent executables are ET_DYN too; they have a PT_INTERP header.
                ET_DYN -> if (hasInterpreter(raf, buffer, is64, order)) ArtifactKind.EXECUTABLE else ArtifactKind.SHARED_LIBRARY
                else -> null
            }
        }
    }.getOrNull()

    private fun hasInterpreter(raf: RandomAccessFile, header: ByteBuffer, is64: Boolean, order: ByteOrder): Boolean {
        val phOffset = if (is64) header.getLong(32) else (header.getInt(28).toLong() and 0xFFFFFFFFL)
        val phEntrySize = header.getShort(if (is64) 54 else 42).toInt() and 0xFFFF
        val phCount = header.getShort(if (is64) 56 else 44).toInt() and 0xFFFF
        if (phEntrySize < 4 || phCount == 0 || phCount > 512) return false
        val table = ByteArray(phEntrySize * phCount)
        if (phOffset + table.size > raf.length()) return false
        raf.seek(phOffset)
        raf.readFully(table)
        val buffer = ByteBuffer.wrap(table).order(order)
        return (0 until phCount).any { buffer.getInt(it * phEntrySize) == PT_INTERP }
    }
}
