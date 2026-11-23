package io.nativeforge.core.export.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decoded `ResStringPool` chunk of a binary XML file. Strings can be appended;
 * existing indices never change, so references elsewhere in the file stay valid.
 */
internal class StringPool private constructor(
    private val strings: MutableList<String>,
    private val flags: Int,
    private val styleOffsets: IntArray,
    private val styleData: ByteArray,
) {
    val size: Int get() = strings.size
    private val isUtf8: Boolean get() = flags and UTF8_FLAG != 0

    operator fun get(index: Int): String? = strings.getOrNull(index)

    /** Returns the index of [value], appending it when missing. */
    fun indexOf(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        strings += value
        return strings.lastIndex
    }

    fun encode(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { index, value ->
            offsets[index] = data.size()
            if (isUtf8) writeUtf8(data, value) else writeUtf16(data, value)
        }
        while (data.size() % 4 != 0) data.write(0)

        val headerSize = 28
        val stringsStart = headerSize + 4 * strings.size + 4 * styleOffsets.size
        val stylesStart = if (styleOffsets.isEmpty()) 0 else stringsStart + data.size()
        val totalSize = stringsStart + data.size() + styleData.size

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(Chunk.STRING_POOL.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putInt(totalSize)
        buffer.putInt(strings.size)
        buffer.putInt(styleOffsets.size)
        // Appended strings break sort order, so clear the SORTED flag.
        buffer.putInt(flags and SORTED_FLAG.inv())
        buffer.putInt(stringsStart)
        buffer.putInt(stylesStart)
        offsets.forEach(buffer::putInt)
        styleOffsets.forEach(buffer::putInt)
        buffer.put(data.toByteArray())
        buffer.put(styleData)
        return buffer.array()
    }

    private fun writeUtf8(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeUtf8Length(out, value.length)
        writeUtf8Length(out, bytes.size)
        out.write(bytes)
        out.write(0)
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        require(length < 0x8000) { "String too long for binary XML" }
        if (length > 0x7F) out.write(((length shr 8) and 0x7F) or 0x80)
        out.write(length and 0xFF)
    }

    private fun writeUtf16(out: ByteArrayOutputStream, value: String) {
        val length = value.length
        if (length > 0x7FFF) {
            writeShort(out, ((length shr 16) and 0x7FFF) or 0x8000)
            writeShort(out, length and 0xFFFF)
        } else {
            writeShort(out, length)
        }
        value.forEach { writeShort(out, it.code) }
        writeShort(out, 0)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    companion object {
        const val UTF8_FLAG = 0x100
        const val SORTED_FLAG = 0x1

        fun decode(chunk: ByteArray): StringPool {
            val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(8)
            val stringCount = buffer.int
            val styleCount = buffer.int
            val flags = buffer.int
            val stringsStart = buffer.int
            val stylesStart = buffer.int
            val offsets = IntArray(stringCount) { buffer.int }
            val styleOffsets = IntArray(styleCount) { buffer.int }
            val utf8 = flags and UTF8_FLAG != 0

            val strings = offsets.mapTo(mutableListOf()) { offset ->
                val position = stringsStart + offset
                if (utf8) readUtf8(chunk, position) else readUtf16(chunk, position)
            }
            val styleData = if (styleCount > 0) chunk.copyOfRange(stylesStart, chunk.size) else ByteArray(0)
            return StringPool(strings, flags, styleOffsets, styleData)
        }

        private fun readUtf8(chunk: ByteArray, start: Int): String {
            var position = start
            fun length(): Int {
                val first = chunk[position++].toInt() and 0xFF
                return if (first and 0x80 != 0) {
                    ((first and 0x7F) shl 8) or (chunk[position++].toInt() and 0xFF)
                } else {
                    first
                }
            }
            length() // UTF-16 length, unused
            val byteLength = length()
            return String(chunk, position, byteLength, Charsets.UTF_8)
        }

        private fun readUtf16(chunk: ByteArray, start: Int): String {
            fun u16(at: Int) = (chunk[at].toInt() and 0xFF) or ((chunk[at + 1].toInt() and 0xFF) shl 8)
            var position = start
            var length = u16(position)
            position += 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7FFF) shl 16) or u16(position)
                position += 2
            }
            val chars = CharArray(length) { i -> u16(position + 2 * i).toChar() }
            return String(chars)
        }
    }
}

/** Chunk type identifiers from ResourceTypes.h. */
internal object Chunk {
    const val STRING_POOL = 0x0001
    const val XML = 0x0003
    const val XML_START_NAMESPACE = 0x0100
    const val XML_START_ELEMENT = 0x0102
    const val XML_RESOURCE_MAP = 0x0180
}
