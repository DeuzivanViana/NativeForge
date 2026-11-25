package io.nativeforge.core.common.crash

/**
 * Turns an Android tombstone (the system's record of a native crash, which Android 12 and
 * later hand back through `ApplicationExitInfo.getTraceInputStream()` as a protocol buffer)
 * into text like `adb logcat` shows: signal, abort message, the crashing thread's backtrace
 * and the last log lines.
 *
 * Only the fields needed for that are read, with a small protobuf reader, so no protobuf
 * library is needed. Field numbers follow AOSP `system/core/debuggerd/proto/tombstone.proto`.
 * When the data does not decode, [decode] falls back to the readable strings in it, which
 * still name the libraries and functions of the backtrace.
 */
object TombstoneDecoder {

    fun decode(bytes: ByteArray, maxLogLines: Int = 60): String {
        val structured = runCatching { decodeStructured(bytes, maxLogLines) }.getOrNull()
        if (structured != null) return structured
        return "Tombstone (not decoded; readable strings follow)\n" + strings(bytes).joinToString("\n")
    }

    private class Frame(
        val relPc: Long,
        val functionName: String,
        val functionOffset: Long,
        val fileName: String,
        val buildId: String,
    )

    private class ThreadInfo(val id: Int, val name: String, val frames: List<Frame>, val notes: List<String>)

    private class LogLine(val timestamp: String, val pid: Int, val tid: Int, val priority: Int, val tag: String, val message: String)

    /** Null when the data does not look like a tombstone. */
    private fun decodeStructured(bytes: ByteArray, maxLogLines: Int): String? {
        var pid = 0
        var tid = 0
        var abort = ""
        var timestamp = ""
        val commandLine = ArrayList<String>()
        var signal: Map<Int, List<Any>> = emptyMap()
        val causes = ArrayList<String>()
        val threads = HashMap<Int, ThreadInfo>()
        val logs = ArrayList<LogLine>()

        val top = ProtoReader(bytes).readAll()
        for ((field, value) in top) {
            when (field) {
                4 -> timestamp = (value as? ByteArray)?.decodeToString().orEmpty()
                5 -> pid = (value as? Long)?.toInt() ?: pid
                6 -> tid = (value as? Long)?.toInt() ?: tid
                9 -> (value as? ByteArray)?.let { commandLine += it.decodeToString() }
                10 -> (value as? ByteArray)?.let { signal = ProtoReader(it).readAll().groupBy({ it.first }, { it.second }) }
                14 -> abort = (value as? ByteArray)?.decodeToString().orEmpty()
                15 -> (value as? ByteArray)?.let { cause ->
                    ProtoReader(cause).readAll().firstOrNull { it.first == 1 }?.let { causes += (it.second as ByteArray).decodeToString() }
                }
                16 -> (value as? ByteArray)?.let { entry ->
                    // map<uint32, Thread>: entries with key = 1, value = 2.
                    val fields = ProtoReader(entry).readAll()
                    val thread = fields.firstOrNull { it.first == 2 }?.second as? ByteArray
                    if (thread != null) readThread(thread).let { threads[it.id] = it }
                }
                18 -> (value as? ByteArray)?.let { buffer ->
                    ProtoReader(buffer).readAll().filter { it.first == 2 }.forEach { logs += readLog(it.second as ByteArray) }
                }
            }
        }
        if (pid == 0 || threads.isEmpty()) return null
        val crashed = threads[tid] ?: threads.values.first()

        return buildString {
            appendLine("Native crash (Android tombstone)")
            if (timestamp.isNotEmpty()) appendLine("Timestamp: $timestamp")
            appendLine("pid: $pid, tid: ${crashed.id}, name: ${crashed.name}  >>> ${commandLine.joinToString(" ")} <<<")
            if (signal.isNotEmpty()) {
                val number = signal.long(1)
                val code = signal.long(3)
                val fault = if (signal.long(8) != 0L) "0x" + java.lang.Long.toHexString(signal.long(9)) else "--------"
                appendLine("signal $number (${signal.string(2)}), code $code (${signal.string(4)}), fault addr $fault")
            }
            if (abort.isNotEmpty()) appendLine("Abort message: '$abort'")
            causes.forEach { appendLine("Cause: $it") }
            crashed.notes.forEach { appendLine("  NOTE: $it") }
            appendLine()
            appendLine("backtrace:")
            crashed.frames.forEachIndexed { index, frame ->
                append("  #%02d pc %016x  %s".format(index, frame.relPc, frame.fileName.ifEmpty { "<unknown>" }))
                if (frame.functionName.isNotEmpty()) append(" (${frame.functionName}+${frame.functionOffset})")
                if (frame.buildId.isNotEmpty()) append(" (BuildId: ${frame.buildId})")
                appendLine()
            }
            val others = threads.values.filter { it !== crashed && it.frames.isNotEmpty() }
            if (others.isNotEmpty()) {
                appendLine()
                appendLine("Other threads: " + others.joinToString { "${it.id} ${it.name}" })
            }
            if (logs.isNotEmpty() && maxLogLines > 0) {
                appendLine()
                appendLine("Last log lines:")
                logs.takeLast(maxLogLines).forEach { line ->
                    appendLine("${line.timestamp} ${line.pid} ${line.tid} ${priorityLetter(line.priority)} ${line.tag}: ${line.message.trimEnd()}")
                }
            }
        }
    }

    private fun readThread(bytes: ByteArray): ThreadInfo {
        var id = 0
        var name = ""
        val frames = ArrayList<Frame>()
        val notes = ArrayList<String>()
        for ((field, value) in ProtoReader(bytes).readAll()) {
            when (field) {
                1 -> id = (value as? Long)?.toInt() ?: id
                2 -> name = (value as? ByteArray)?.decodeToString().orEmpty()
                4 -> (value as? ByteArray)?.let { frames += readFrame(it) }
                7 -> (value as? ByteArray)?.let { notes += it.decodeToString() }
            }
        }
        return ThreadInfo(id, name, frames, notes)
    }

    private fun readFrame(bytes: ByteArray): Frame {
        val fields = ProtoReader(bytes).readAll().groupBy({ it.first }, { it.second })
        return Frame(
            relPc = fields.long(1),
            functionName = fields.string(4),
            functionOffset = fields.long(5),
            fileName = fields.string(6),
            buildId = fields.string(8),
        )
    }

    private fun readLog(bytes: ByteArray): LogLine {
        val fields = ProtoReader(bytes).readAll().groupBy({ it.first }, { it.second })
        return LogLine(
            timestamp = fields.string(1),
            pid = fields.long(2).toInt(),
            tid = fields.long(3).toInt(),
            priority = fields.long(4).toInt(),
            tag = fields.string(5),
            message = fields.string(6),
        )
    }

    private fun Map<Int, List<Any>>.long(field: Int): Long = this[field]?.lastOrNull() as? Long ?: 0L

    private fun Map<Int, List<Any>>.string(field: Int): String =
        (this[field]?.lastOrNull() as? ByteArray)?.decodeToString().orEmpty()

    private fun priorityLetter(priority: Int) = when (priority) {
        2 -> "V"; 3 -> "D"; 4 -> "I"; 5 -> "W"; 6 -> "E"; 7 -> "F"; else -> "?"
    }

    /** Runs of at least [minLength] printable ASCII characters, like the `strings` tool. */
    fun strings(bytes: ByteArray, minLength: Int = 6, limit: Int = 400): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= minLength && result.size < limit) result += current.toString()
            current.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) current.append(c.toChar()) else flush()
        }
        flush()
        return result
    }

    /**
     * Reads the fields of one protobuf message: (field number, value) pairs, where a value is a
     * [Long] (varint and fixed-size fields) or a [ByteArray] (length-delimited: strings,
     * nested messages, packed fields). Throws on malformed data.
     */
    internal class ProtoReader(private val bytes: ByteArray) {
        private var position = 0

        fun readAll(): List<Pair<Int, Any>> {
            val fields = ArrayList<Pair<Int, Any>>()
            while (position < bytes.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                require(field > 0) { "bad field number" }
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> fixed(8)
                    2 -> {
                        val length = varint()
                        require(length >= 0 && position + length <= bytes.size) { "bad length" }
                        bytes.copyOfRange(position, position + length.toInt()).also { position += length.toInt() }
                    }
                    5 -> fixed(4)
                    else -> throw IllegalArgumentException("unsupported wire type")
                }
                fields += field to value
            }
            return fields
        }

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(position < bytes.size && shift < 64) { "truncated varint" }
                val b = bytes[position++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun fixed(size: Int): Long {
            require(position + size <= bytes.size) { "truncated fixed field" }
            var result = 0L
            for (i in 0 until size) result = result or ((bytes[position + i].toLong() and 0xff) shl (8 * i))
            position += size
            return result
        }
    }
}
