package io.nativeforge.core.export.bundle

import java.io.ByteArrayOutputStream

/**
 * Minimal protocol buffers wire-format codec. It keeps every field, known or not, in
 * order, so a message can be decoded, edited and encoded again without a schema
 * compiler or the protobuf runtime (which would add about 2 MB to the IDE).
 */
internal class ProtoMessage(val fields: MutableList<ProtoField> = mutableListOf()) {

    fun encode(): ByteArray = ByteArrayOutputStream().also { out -> fields.forEach { it.writeTo(out) } }.toByteArray()

    /** Length-delimited fields with [number], decoded as messages. */
    fun messages(number: Int): List<ProtoMessage> =
        fields.filter { it.number == number && it is ProtoField.Bytes }.map { decode((it as ProtoField.Bytes).value) }

    fun message(number: Int): ProtoMessage? = messages(number).firstOrNull()

    fun string(number: Int): String? =
        (fields.firstOrNull { it.number == number && it is ProtoField.Bytes } as? ProtoField.Bytes)?.value?.decodeToString()

    fun varint(number: Int): Long? = (fields.firstOrNull { it.number == number && it is ProtoField.Varint } as? ProtoField.Varint)?.value

    /** Replaces every field with [number] by one string field, or appends it. */
    fun setString(number: Int, value: String) = setBytes(number, value.encodeToByteArray())

    fun setMessage(number: Int, value: ProtoMessage) = setBytes(number, value.encode())

    fun setVarint(number: Int, value: Long) = set(ProtoField.Varint(number, value))

    fun remove(number: Int) {
        fields.removeAll { it.number == number }
    }

    /** Rewrites each length-delimited field with [number] through [transform]. */
    fun updateMessages(number: Int, transform: (ProtoMessage) -> Unit) {
        for (i in fields.indices) {
            val field = fields[i]
            if (field.number == number && field is ProtoField.Bytes) {
                val message = decode(field.value)
                transform(message)
                fields[i] = ProtoField.Bytes(number, message.encode())
            }
        }
    }

    private fun setBytes(number: Int, value: ByteArray) = set(ProtoField.Bytes(number, value))

    private fun set(field: ProtoField) {
        val first = fields.indexOfFirst { it.number == field.number }
        fields.removeAll { it.number == field.number }
        if (first < 0) fields += field else fields.add(first.coerceAtMost(fields.size), field)
    }

    companion object {
        fun decode(bytes: ByteArray): ProtoMessage {
            val reader = Reader(bytes)
            val fields = mutableListOf<ProtoField>()
            while (!reader.atEnd()) {
                val key = reader.varint()
                val number = (key ushr 3).toInt()
                require(number > 0) { "Invalid protobuf field number" }
                fields += when (val wireType = (key and 7).toInt()) {
                    0 -> ProtoField.Varint(number, reader.varint())
                    1 -> ProtoField.Fixed64(number, reader.bytes(8))
                    2 -> ProtoField.Bytes(number, reader.bytes(reader.varint().toInt()))
                    5 -> ProtoField.Fixed32(number, reader.bytes(4))
                    else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
                }
            }
            return ProtoMessage(fields)
        }
    }

    private class Reader(private val bytes: ByteArray) {
        private var position = 0

        fun atEnd() = position >= bytes.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(position < bytes.size && shift < 64) { "Truncated protobuf varint" }
                val b = bytes[position++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun bytes(length: Int): ByteArray {
            require(length >= 0 && position + length <= bytes.size) { "Truncated protobuf field" }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }
    }
}

internal sealed class ProtoField(val number: Int) {
    abstract fun writeTo(out: ByteArrayOutputStream)

    class Varint(number: Int, val value: Long) : ProtoField(number) {
        override fun writeTo(out: ByteArrayOutputStream) {
            writeVarint(out, (number.toLong() shl 3) or 0)
            writeVarint(out, value)
        }
    }

    class Fixed64(number: Int, val value: ByteArray) : ProtoField(number) {
        override fun writeTo(out: ByteArrayOutputStream) {
            writeVarint(out, (number.toLong() shl 3) or 1)
            out.write(value)
        }
    }

    class Bytes(number: Int, val value: ByteArray) : ProtoField(number) {
        override fun writeTo(out: ByteArrayOutputStream) {
            writeVarint(out, (number.toLong() shl 3) or 2)
            writeVarint(out, value.size.toLong())
            out.write(value)
        }
    }

    class Fixed32(number: Int, val value: ByteArray) : ProtoField(number) {
        override fun writeTo(out: ByteArrayOutputStream) {
            writeVarint(out, (number.toLong() shl 3) or 5)
            out.write(value)
        }
    }

    protected fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
