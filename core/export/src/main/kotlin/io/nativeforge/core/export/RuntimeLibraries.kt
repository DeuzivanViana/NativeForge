package io.nativeforge.core.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Picks the shared libraries an exported program really loads. A build passes every library
 * of every declared package (and libc++_shared); an APK only needs the ones reachable from the
 * program, which keeps exported apps small.
 *
 * A library is kept when its file name or soname appears in the program, or in a library
 * already kept. That covers DT_NEEDED entries (the names sit in the dynamic string table) and
 * libraries opened with `dlopen("libvorbisfile.so")` by a string literal, as SDL_mixer does
 * for its codecs. The library order is kept, since it is the preload order.
 */
object RuntimeLibraries {

    fun required(program: File, candidates: List<File>): List<File> {
        if (candidates.isEmpty()) return candidates
        // Not an ELF file we understand: keep everything rather than break the app.
        if (ElfDynamicInfo.read(program) == null) return candidates
        val names = candidates.associateWith { lib -> setOfNotNull(lib.name, ElfDynamicInfo.read(lib)?.soname) }
        val kept = LinkedHashSet<File>()
        val pending = ArrayDeque<File>().apply { add(program) }
        while (pending.isNotEmpty()) {
            // Latin-1 maps each byte to one char, so names are found wherever they are stored.
            val content = String(pending.removeFirst().readBytes(), Charsets.ISO_8859_1)
            for (lib in candidates) {
                if (lib !in kept && names.getValue(lib).any(content::contains)) {
                    kept += lib
                    pending += lib
                }
            }
        }
        return candidates.filter { it in kept }
    }
}

/** The parts of an ELF file's dynamic section that matter for bundling. */
data class ElfDynamicInfo(val soname: String?, val needed: List<String>) {

    companion object {
        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2
        private const val DT_NULL = 0L
        private const val DT_NEEDED = 1L
        private const val DT_STRTAB = 5L
        private const val DT_SONAME = 14L

        /** Reads a little-endian 32- or 64-bit ELF file; null when it is not one or is malformed. */
        fun read(file: File): ElfDynamicInfo? = runCatching { parse(file.readBytes()) }.getOrNull()

        internal fun parse(bytes: ByteArray): ElfDynamicInfo? {
            if (bytes.size < 52 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
            ) return null
            val is64 = when (bytes[4].toInt()) {
                1 -> false
                2 -> true
                else -> return null
            }
            if (bytes[5].toInt() != 1) return null // Android is little-endian only.
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun word(offset: Long): Long = if (is64) buffer.getLong(offset.toInt()) else buffer.getInt(offset.toInt()).toLong() and 0xffffffffL

            val phoff = if (is64) buffer.getLong(0x20) else buffer.getInt(0x1C).toLong() and 0xffffffffL
            val phentsize = buffer.getShort(if (is64) 0x36 else 0x2A).toInt() and 0xffff
            val phnum = buffer.getShort(if (is64) 0x38 else 0x2C).toInt() and 0xffff

            data class Segment(val type: Int, val offset: Long, val vaddr: Long, val filesz: Long)
            val segments = (0 until phnum).map { i ->
                val base = phoff + i.toLong() * phentsize
                val type = buffer.getInt(base.toInt())
                if (is64) {
                    Segment(type, buffer.getLong((base + 8).toInt()), buffer.getLong((base + 16).toInt()), buffer.getLong((base + 32).toInt()))
                } else {
                    Segment(type, word(base + 4), word(base + 8), word(base + 16))
                }
            }
            // A static executable has no dynamic section, and needs no libraries.
            val dynamic = segments.firstOrNull { it.type == PT_DYNAMIC } ?: return ElfDynamicInfo(null, emptyList())
            fun fileOffset(address: Long): Long? =
                segments.firstOrNull { it.type == PT_LOAD && address >= it.vaddr && address < it.vaddr + it.filesz }
                    ?.let { address - it.vaddr + it.offset }

            val entrySize = if (is64) 16 else 8
            var strtab: Long? = null
            var soname: Long? = null
            val needed = mutableListOf<Long>()
            var entry = dynamic.offset
            while (entry + entrySize <= dynamic.offset + dynamic.filesz) {
                val tag = word(entry)
                val value = word(entry + entrySize / 2)
                when (tag) {
                    DT_NULL -> break
                    DT_NEEDED -> needed += value
                    DT_STRTAB -> strtab = value
                    DT_SONAME -> soname = value
                }
                entry += entrySize
            }
            val strings = strtab?.let(::fileOffset) ?: return ElfDynamicInfo(null, emptyList())
            fun string(index: Long): String {
                val start = (strings + index).toInt()
                var end = start
                while (bytes[end] != 0.toByte()) end++
                return String(bytes, start, end - start, Charsets.UTF_8)
            }
            return ElfDynamicInfo(soname?.let(::string), needed.map(::string))
        }
    }
}
