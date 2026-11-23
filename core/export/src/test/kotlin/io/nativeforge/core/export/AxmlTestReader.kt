package io.nativeforge.core.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Tiny independent AXML reader used to verify patched output: element -> attribute name -> value. */
object AxmlTestReader {

    fun read(bytes: ByteArray): List<Pair<String, Map<String, Any>>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = 8
        var strings = emptyList<String>()
        val result = mutableListOf<Pair<String, Map<String, Any>>>()
        while (position < bytes.size) {
            val type = buffer.getShort(position).toInt() and 0xFFFF
            val size = buffer.getInt(position + 4)
            when (type) {
                0x0001 -> strings = readPool(buffer, position)
                0x0102 -> {
                    val name = strings[buffer.getInt(position + 20)]
                    val count = buffer.getShort(position + 28).toInt()
                    val attrs = (0 until count).associate { i ->
                        val a = position + 36 + i * 20
                        val attrName = strings[buffer.getInt(a + 4)]
                        val dataType = buffer.get(a + 15).toInt()
                        val data = buffer.getInt(a + 16)
                        attrName to (if (dataType == 0x03) strings[data] else data)
                    }
                    result += name to attrs
                }
            }
            position += size
        }
        check(buffer.getInt(4) == bytes.size) { "Header size does not match file size" }
        return result
    }

    private fun readPool(buffer: ByteBuffer, start: Int): List<String> {
        val count = buffer.getInt(start + 8)
        val utf8 = buffer.getInt(start + 16) and 0x100 != 0
        val stringsStart = buffer.getInt(start + 20)
        return (0 until count).map { i ->
            var p = start + stringsStart + buffer.getInt(start + 28 + 4 * i)
            if (utf8) {
                fun len(): Int {
                    val b = buffer.get(p++).toInt() and 0xFF
                    return if (b and 0x80 != 0) ((b and 0x7F) shl 8) or (buffer.get(p++).toInt() and 0xFF) else b
                }
                len(); val n = len()
                String(ByteArray(n) { buffer.get(p + it) }, Charsets.UTF_8)
            } else {
                val n = buffer.getShort(p).toInt() and 0xFFFF
                String(CharArray(n) { buffer.getChar(p + 2 + 2 * it) })
            }
        }
    }
}
