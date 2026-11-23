package io.nativeforge.core.lsp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Error returned by the server for a request. */
class JsonRpcException(val code: Int, message: String) : IOException(message)

/**
 * JSON-RPC 2.0 over the LSP base protocol (`Content-Length` framed messages).
 *
 * A daemon thread reads incoming messages; writes are synchronized so requests
 * can be sent from any thread. Requests return [CompletableFuture]s, which suits
 * both coroutines (`await()`) and the editor's completion thread (`get(timeout)`).
 */
class JsonRpcConnection(
    input: InputStream,
    private val output: OutputStream,
    private val name: String = "lsp",
) {
    /** Server-to-client notifications (`textDocument/publishDiagnostics`...). */
    @Volatile var onNotification: (method: String, params: JsonElement?) -> Unit = { _, _ -> }

    /** Server-to-client requests; the return value is the result (JsonNull when there is none). */
    @Volatile var onRequest: (method: String, params: JsonElement?) -> JsonElement = { _, _ -> JsonNull }

    /** Called once when the stream ends or fails. */
    @Volatile var onClosed: (Throwable?) -> Unit = {}

    private val input = BufferedInputStream(input)
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    @Volatile private var closed = false

    private val reader = Thread({ readLoop() }, "$name-reader").apply { isDaemon = true }

    fun start(): JsonRpcConnection = apply { reader.start() }

    val isClosed: Boolean get() = closed

    fun request(method: String, params: JsonElement? = null): CompletableFuture<JsonElement> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        if (closed) return future.apply { completeExceptionally(IOException("$name connection is closed")) }
        pending[id] = future
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        try {
            write(message)
        } catch (e: IOException) {
            pending.remove(id)
            future.completeExceptionally(e)
        }
        return future
    }

    /** Cancels a request the caller no longer needs (for example an outdated completion). */
    fun cancel(future: CompletableFuture<JsonElement>) {
        val id = pending.entries.firstOrNull { it.value === future }?.key ?: return
        pending.remove(id)
        future.cancel(false)
        runCatching { notify("\$/cancelRequest", buildJsonObject { put("id", id) }) }
    }

    fun notify(method: String, params: JsonElement? = null) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                if (params != null) put("params", params)
            },
        )
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { output.close() }
        failPending(IOException("$name connection closed"))
    }

    private fun write(message: JsonObject) {
        val body = LspJson.encodeToString(JsonElement.serializer(), message).toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        synchronized(output) {
            if (closed) throw IOException("$name connection is closed")
            output.write(header)
            output.write(body)
            output.flush()
        }
    }

    private fun readLoop() {
        var failure: Throwable? = null
        try {
            while (!closed) {
                val message = readMessage() ?: break
                dispatch(message)
            }
        } catch (e: Throwable) {
            failure = e
        } finally {
            closed = true
            failPending(failure ?: EOFException("$name server exited"))
            onClosed(failure)
        }
    }

    /** Reads one framed message, or null at end of stream. */
    internal fun readMessage(): JsonObject? {
        var length = -1
        while (true) {
            val line = readHeaderLine() ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toInt()
            }
        }
        if (length < 0) throw IOException("Missing Content-Length header")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("Truncated message")
            read += n
        }
        return LspJson.parseToJsonElement(body.decodeToString()).jsonObject
    }

    private fun readHeaderLine(): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
    }

    private fun dispatch(message: JsonObject) {
        val method = message["method"]?.jsonPrimitive?.contentOrNull
        val id = message["id"]
        when {
            method != null && id != null && id !is JsonNull -> {
                val result = runCatching { onRequest(method, message["params"]) }.getOrDefault(JsonNull)
                runCatching {
                    write(
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("result", result)
                        },
                    )
                }
            }
            method != null -> runCatching { onNotification(method, message["params"]) }
            id != null -> {
                val future = (id as? JsonPrimitive)?.intOrNull?.let(pending::remove) ?: return
                val error = message["error"] as? JsonObject
                if (error != null) {
                    val code = error["code"]?.jsonPrimitive?.intOrNull ?: -1
                    val text = error["message"]?.jsonPrimitive?.contentOrNull ?: "error $code"
                    future.completeExceptionally(JsonRpcException(code, text))
                } else {
                    future.complete(message["result"] ?: JsonNull)
                }
            }
        }
    }

    private fun failPending(error: Throwable) {
        val futures = pending.values.toList()
        pending.clear()
        futures.forEach { it.completeExceptionally(error) }
    }
}

/** JSON configuration shared by the LSP code: servers add fields freely. */
val LspJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
