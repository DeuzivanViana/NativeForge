package io.nativeforge.core.remote.http

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Receives the messages of one [WebSocket]. Called on the connection's thread. */
interface WebSocketListener {
    fun onOpen(socket: WebSocket) {}
    fun onText(socket: WebSocket, text: String) {}
    fun onBinary(socket: WebSocket, data: ByteArray) {}
    fun onClose(socket: WebSocket) {}
}

/**
 * Server side of an RFC 6455 WebSocket, without extensions. Client frames must be masked,
 * control frames are answered in place (ping/pong/close) and fragmented messages are joined
 * up to [maxMessageBytes]. Sending is thread safe.
 */
class WebSocket internal constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
    private val maxMessageBytes: Int,
) {
    @Volatile var isOpen: Boolean = true
        private set

    /** Free slot for whoever handles the socket (for example a session id). */
    @Volatile var attachment: Any? = null

    fun sendText(text: String) = send(OP_TEXT, text.toByteArray(StandardCharsets.UTF_8))

    fun sendBinary(data: ByteArray, offset: Int = 0, length: Int = data.size) = send(OP_BINARY, data, offset, length)

    fun close(code: Int = 1000, reason: String = "") {
        if (!isOpen) return
        val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8).take(120).toByteArray()
        val payload = byteArrayOf((code shr 8).toByte(), code.toByte()) + reasonBytes
        runCatching { send(OP_CLOSE, payload) }
        isOpen = false
        runCatching { socket.close() }
    }

    /** Sends a ping; the browser answers with a pong, which keeps idle connections alive. */
    fun ping() = send(OP_PING, ByteArray(0))

    private fun send(opcode: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!isOpen) throw IOException("WebSocket is closed")
        synchronized(output) {
            output.write(writeFrameHeader(opcode, length))
            output.write(data, offset, length)
            output.flush()
        }
    }

    /** Reads messages until the connection closes; blocks the calling thread. */
    internal fun run(listener: WebSocketListener) {
        listener.onOpen(this)
        val message = ByteArrayOutputStream()
        var messageOpcode = -1
        try {
            while (isOpen) {
                val frame = readFrame()
                when (frame.opcode) {
                    OP_CONTINUATION -> {
                        if (messageOpcode < 0) throw IOException("Unexpected continuation frame")
                        append(message, frame.payload)
                    }
                    OP_TEXT, OP_BINARY -> {
                        if (messageOpcode >= 0) throw IOException("New message inside a fragmented one")
                        messageOpcode = frame.opcode
                        message.reset()
                        append(message, frame.payload)
                    }
                    OP_PING -> { send(OP_PONG, frame.payload); continue }
                    OP_PONG -> continue
                    OP_CLOSE -> { close(); break }
                    else -> throw IOException("Unknown opcode ${frame.opcode}")
                }
                if (frame.fin) {
                    val bytes = message.toByteArray()
                    message.reset()
                    val opcode = messageOpcode
                    messageOpcode = -1
                    if (opcode == OP_TEXT) listener.onText(this, String(bytes, StandardCharsets.UTF_8))
                    else listener.onBinary(this, bytes)
                }
            }
        } catch (e: IOException) {
            // Closed by the peer, a timeout or a protocol error: the connection ends either way.
            if (isOpen) close(if (e is EOFException) 1001 else 1002)
        } finally {
            isOpen = false
            runCatching { socket.close() }
            listener.onClose(this)
        }
    }

    private fun append(message: ByteArrayOutputStream, payload: ByteArray) {
        if (message.size() + payload.size > maxMessageBytes) {
            close(1009, "Message too big")
            throw IOException("Message too big")
        }
        message.write(payload)
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(): Frame {
        val b0 = readByte()
        val b1 = readByte()
        if (b0 and 0x70 != 0) throw IOException("Reserved bits set (no extension was negotiated)")
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        if (b1 and 0x80 == 0) throw IOException("Client frames must be masked")
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) length = ((readByte() shl 8) or readByte()).toLong()
        else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte().toLong() }
        }
        val isControl = opcode >= 0x8
        if (isControl && (length > 125 || !fin)) throw IOException("Bad control frame")
        if (length > maxMessageBytes) {
            close(1009, "Message too big")
            throw IOException("Frame too big")
        }
        val mask = ByteArray(4).also { readFully(it) }
        val payload = ByteArray(length.toInt()).also { readFully(it) }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val n = input.read(target, read, target.size - read)
            if (n < 0) throw EOFException()
            read += n
        }
    }

    companion object {
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** The `Sec-WebSocket-Accept` value for a client's key. */
        fun acceptKey(clientKey: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest((clientKey.trim() + GUID).toByteArray(StandardCharsets.ISO_8859_1))
            return Base64.getEncoder().encodeToString(digest)
        }

        /** True when [key] is the base64 of 16 bytes, as RFC 6455 requires. */
        fun isValidKey(key: String?): Boolean =
            key != null && runCatching { Base64.getDecoder().decode(key.trim()).size == 16 }.getOrDefault(false)

        internal fun writeFrameHeader(opcode: Int, length: Int): ByteArray = when {
            length < 126 -> byteArrayOf((0x80 or opcode).toByte(), length.toByte())
            length < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (length shr 8).toByte(), length.toByte())
            else -> ByteArray(10).also { h ->
                h[0] = (0x80 or opcode).toByte()
                h[1] = 127
                for (i in 0 until 8) h[9 - i] = (length.toLong() shr (8 * i)).toByte()
            }
        }
    }
}
