package io.nativeforge.core.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds minimal binary XML documents for tests, mirroring the layout aapt2
 * produces: string pool, resource map, namespace and element chunks.
 */
class AxmlTestBuilder(private val utf8: Boolean = true) {

    data class Attr(val name: String, val resourceId: Int, val type: Int, val value: Any)
    private data class Element(val name: String, val attrs: List<Attr>)

    private val elements = mutableListOf<Element>()

    fun element(name: String, vararg attrs: Attr) = apply { elements += Element(name, attrs.toList()) }

    fun build(): ByteArray {
        // Resource-mapped attribute names must come first in the pool.
        val mapped = elements.flatMap { it.attrs }.filter { it.resourceId != 0 }.map { it.name to it.resourceId }.distinct()
        val strings = mutableListOf<String>()
        mapped.forEach { strings += it.first }
        fun idx(s: String): Int = strings.indexOf(s).takeIf { it >= 0 } ?: run { strings += s; strings.lastIndex }
        idx("android"); idx(ANDROID_NS)

        val body = ByteArrayOutputStream()
        body.write(chunk(0x0100, 16) { putInt(1); putInt(-1); putInt(idx("android")); putInt(idx(ANDROID_NS)) })
        elements.forEach { element ->
            val attrBytes = element.attrs.map { attr ->
                val nameIndex = if (attr.resourceId != 0) mapped.indexOfFirst { it.first == attr.name } else idx(attr.name)
                val ns = if (attr.resourceId != 0) idx(ANDROID_NS) else -1
                val (raw, data) = when (attr.type) {
                    0x03 -> idx(attr.value as String).let { it to it }
                    else -> -1 to (attr.value as Int)
                }
                ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putInt(ns); putInt(nameIndex); putInt(raw); putShort(8); put(0); put(attr.type.toByte()); putInt(data)
                }.array()
            }
            val elementName = idx(element.name)
            body.write(chunk(0x0102, 16) {
                putInt(1); putInt(-1)
                putInt(-1); putInt(elementName)
                putShort(20); putShort(20); putShort(attrBytes.size.toShort()); putShort(0); putShort(0); putShort(0)
                attrBytes.forEach { put(it) }
            })
        }
        elements.reversed().forEach { element ->
            body.write(chunk(0x0103, 16) { putInt(1); putInt(-1); putInt(-1); putInt(idx(element.name)) })
        }

        val pool = stringPool(strings)
        val resourceMap = chunk(0x0180, 8) { mapped.forEach { putInt(it.second) } }
        val content = pool + resourceMap + body.toByteArray()
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x0003).putShort(8).putInt(8 + content.size).array() + content
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val offset = data.size()
            if (utf8) {
                val bytes = s.toByteArray()
                data.write(s.length); data.write(bytes.size); data.write(bytes); data.write(0)
            } else {
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                s.forEach { data.write(it.code and 0xFF); data.write(it.code shr 8) }
                data.write(0); data.write(0)
            }
            offset
        }
        while (data.size() % 4 != 0) data.write(0)
        val stringsStart = 28 + 4 * strings.size
        val size = stringsStart + data.size()
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0x0001); putShort(28); putInt(size); putInt(strings.size); putInt(0)
            putInt(if (utf8) 0x100 else 0); putInt(stringsStart); putInt(0)
            offsets.forEach { putInt(it) }
            put(data.toByteArray())
        }.array()
    }

    private fun chunk(type: Int, headerSize: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(8)
        buffer.body()
        val size = buffer.position()
        buffer.putShort(0, type.toShort()); buffer.putShort(2, headerSize.toShort()); buffer.putInt(4, size)
        return buffer.array().copyOf(size)
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
