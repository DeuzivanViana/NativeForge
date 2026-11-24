package io.nativeforge.core.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeLibrariesTest {
    private val temp = kotlin.io.path.createTempDirectory("nf-libs").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    @Test
    fun `reads soname and needed entries of 64 and 32 bit files`() {
        for (is64 in listOf(true, false)) {
            val info = ElfDynamicInfo.parse(elf(is64, soname = "libSDL3.so", needed = listOf("libc++_shared.so", "libm.so")))
            assertEquals(ElfDynamicInfo("libSDL3.so", listOf("libc++_shared.so", "libm.so")), info, "64-bit: $is64")
        }
    }

    @Test
    fun `non elf input is rejected`() {
        assertNull(ElfDynamicInfo.parse("#!/bin/sh\necho hi\n".padEnd(64).toByteArray()))
    }

    @Test
    fun `keeps only libraries reachable from the program, in the original order`() {
        val cxx = lib("libc++_shared.so", elf(soname = "libc++_shared.so"))
        val sdl = lib("libSDL3.so", elf(soname = "libSDL3.so", needed = listOf("libc++_shared.so")))
        val lua = lib("liblua5.4.so", elf(soname = "liblua5.4.so"))
        val box2d = lib("libbox2d.so", elf(soname = "libbox2d.so"))
        val program = lib("libmain.so", elf(needed = listOf("libSDL3.so", "libdl.so")))

        assertEquals(listOf(cxx, sdl), RuntimeLibraries.required(program, listOf(cxx, sdl, lua, box2d)))
    }

    @Test
    fun `a C program drops libc++ and unused package libraries`() {
        val cxx = lib("libc++_shared.so", elf(soname = "libc++_shared.so"))
        val program = lib("hello", elf(needed = listOf("libc.so")))
        assertEquals(emptyList(), RuntimeLibraries.required(program, listOf(cxx)))
    }

    @Test
    fun `matches a library by its soname when the file name differs`() {
        val png = lib("libpng.so", elf(soname = "libpng16.so"))
        val program = lib("libmain.so", elf(needed = listOf("libpng16.so")))
        assertEquals(listOf(png), RuntimeLibraries.required(program, listOf(png)))
    }

    @Test
    fun `keeps libraries opened with dlopen by name`() {
        val vorbis = lib("libvorbisfile.so", elf(soname = "libvorbisfile.so"))
        val mixer = lib("libSDL3_mixer.so", elf(soname = "libSDL3_mixer.so", extra = "libvorbisfile.so.3"))
        val program = lib("libmain.so", elf(needed = listOf("libSDL3_mixer.so")))
        assertEquals(listOf(vorbis, mixer), RuntimeLibraries.required(program, listOf(vorbis, mixer)))
    }

    @Test
    fun `keeps everything when the program cannot be analysed`() {
        val sdl = lib("libSDL3.so", elf(soname = "libSDL3.so"))
        val program = lib("libmain.so", "not an elf file".toByteArray())
        assertEquals(listOf(sdl), RuntimeLibraries.required(program, listOf(sdl)))
    }

    private fun lib(name: String, bytes: ByteArray) = File(temp, name).apply { writeBytes(bytes) }

    /**
     * A minimal little-endian ELF file: one PT_LOAD segment mapping the whole file at address 0,
     * a PT_DYNAMIC segment, the dynamic string table and optional trailing data ([extra]).
     */
    private fun elf(is64: Boolean = true, soname: String? = null, needed: List<String> = emptyList(), extra: String = ""): ByteArray {
        val headerSize = if (is64) 64 else 52
        val phentsize = if (is64) 56 else 32
        val strtabOffset = headerSize + 2 * phentsize
        val strings = ByteArrayOutputStream().apply { write(0) }
        fun add(s: String): Long = strings.size().toLong().also { strings.write(s.toByteArray()); strings.write(0) }
        val dyn = mutableListOf<Pair<Long, Long>>()
        needed.forEach { dyn += 1L to add(it) }
        soname?.let { dyn += 14L to add(it) }
        dyn += 5L to strtabOffset.toLong()
        dyn += 0L to 0L
        val dynOffset = (strtabOffset + strings.size() + 7) / 8 * 8
        val entrySize = if (is64) 16 else 8
        val dynSize = dyn.size * entrySize
        val total = dynOffset + dynSize + extra.length + 1
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), if (is64) 2 else 1, 1, 1))
        if (is64) {
            b.putLong(0x20, headerSize.toLong()); b.putShort(0x36, phentsize.toShort()); b.putShort(0x38, 2)
        } else {
            b.putInt(0x1C, headerSize); b.putShort(0x2A, phentsize.toShort()); b.putShort(0x2C, 2)
        }
        fun phdr(index: Int, type: Int, offset: Long, size: Long) {
            val base = headerSize + index * phentsize
            b.putInt(base, type)
            if (is64) {
                b.putLong(base + 8, offset); b.putLong(base + 16, offset); b.putLong(base + 32, size)
            } else {
                b.putInt(base + 4, offset.toInt()); b.putInt(base + 8, offset.toInt()); b.putInt(base + 16, size.toInt())
            }
        }
        phdr(0, 1, 0, total.toLong())
        phdr(1, 2, dynOffset.toLong(), dynSize.toLong())
        b.position(strtabOffset); b.put(strings.toByteArray())
        b.position(dynOffset)
        dyn.forEach { (tag, value) -> if (is64) b.putLong(tag).putLong(value) else b.putInt(tag.toInt()).putInt(value.toInt()) }
        b.put(extra.toByteArray())
        return b.array()
    }
}
