package io.nativeforge.core.remote.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** A parsed HTTP/1.1 request. Header names are lower case. */
class HttpRequest(
    val method: String,
    /** Path without the query, percent-decoded. */
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddress: InetAddress,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun bodyText(): String = String(body, StandardCharsets.UTF_8)

    /** Value of the cookie [name], if the request carries it. */
    fun cookie(name: String): String? = header("cookie")
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

    val keepAlive: Boolean get() = header("connection")?.lowercase()?.contains("close") != true

    val isWebSocketUpgrade: Boolean
        get() = method == "GET" &&
            header("upgrade")?.lowercase() == "websocket" &&
            header("connection")?.lowercase()?.contains("upgrade") == true
}

/** A response; [body] is sent as is unless [HttpServer] compresses it. */
class HttpResponse(
    val status: Int,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
    val headers: MutableMap<String, String> = linkedMapOf(),
) {
    fun header(name: String, value: String) = apply { headers[name] = value }

    companion object {
        fun text(status: Int, text: String, type: String = "text/plain; charset=utf-8") =
            HttpResponse(status, type, text.toByteArray(StandardCharsets.UTF_8))

        fun json(text: String, status: Int = 200) = text(status, text, "application/json; charset=utf-8")

        fun error(status: Int, message: String) =
            json("{\"error\":${io.nativeforge.core.remote.Json.quote(message)}}", status)

        fun empty(status: Int = 204) = HttpResponse(status)
    }
}

/** Thrown for requests the server refuses before routing them (malformed or too large). */
class HttpProtocolException(val status: Int, message: String) : IOException(message)

/**
 * Reads one request from [input]. Returns null when the client closed the connection
 * cleanly before sending anything (the end of a keep-alive connection).
 *
 * Limits: [maxHeaderBytes] for the request line and headers together, [maxBodyBytes] for the
 * body. Only `Content-Length` bodies are accepted; chunked request bodies are refused.
 */
object HttpParser {
    fun read(input: InputStream, remote: InetAddress, maxHeaderBytes: Int, maxBodyBytes: Int): HttpRequest? {
        val head = readHead(input, maxHeaderBytes) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) {
            throw HttpProtocolException(400, "Malformed request line")
        }
        val method = requestLine[0]
        if (method !in METHODS) throw HttpProtocolException(405, "Method not allowed")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpProtocolException(400, "Malformed header")
            val name = line.substring(0, colon).trim().lowercase()
            // Duplicate Host or Content-Length headers are a request smuggling signal.
            if (name in SINGLE_HEADERS && name in headers) throw HttpProtocolException(400, "Duplicate $name header")
            headers[name] = line.substring(colon + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpProtocolException(411, "Chunked request bodies are not supported")
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw HttpProtocolException(400, "Bad Content-Length") } ?: 0L
        if (length < 0) throw HttpProtocolException(400, "Bad Content-Length")
        if (length > maxBodyBytes) throw HttpProtocolException(413, "Request body too large")
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw IOException("Connection closed while reading the body")
            read += n
        }
        val target = requestLine[1]
        if (!target.startsWith("/")) throw HttpProtocolException(400, "Only origin-form request targets are accepted")
        val rawPath = target.substringBefore('?')
        val path = decode(rawPath, plusIsSpace = false)
        if (path.contains('\u0000')) throw HttpProtocolException(400, "Bad path")
        return HttpRequest(method, path, parseQuery(target.substringAfter('?', "")), headers, body, remote)
    }

    fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='), plusIsSpace = true)
            result.putIfAbsent(key, decode(pair.substringAfter('=', ""), plusIsSpace = true))
        }
        return result
    }

    private fun decode(value: String, plusIsSpace: Boolean): String = try {
        URLDecoder.decode(if (plusIsSpace) value else value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        throw HttpProtocolException(400, "Bad percent-encoding")
    }

    /** Bytes up to and excluding the blank line, as ISO-8859-1 text. */
    private fun readHead(input: InputStream, limit: Int): String? {
        val buffer = ByteArrayOutputStream(512)
        var state = 0 // count of "\r\n\r\n" bytes matched so far
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buffer.size() == 0) return null
                throw IOException("Connection closed inside the request head")
            }
            // Tolerate blank lines before the request line (RFC 9112 section 2.2).
            if (buffer.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
            buffer.write(b)
            if (buffer.size() > limit) throw HttpProtocolException(431, "Request header too large")
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, bytes.size - 4, StandardCharsets.ISO_8859_1)
            }
        }
    }

    private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE")
    private val SINGLE_HEADERS = setOf("host", "content-length")
}

/** Reason phrases for the status codes the server uses. */
internal fun reasonPhrase(status: Int): String = when (status) {
    101 -> "Switching Protocols"
    200 -> "OK"
    201 -> "Created"
    204 -> "No Content"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    411 -> "Length Required"
    413 -> "Payload Too Large"
    415 -> "Unsupported Media Type"
    421 -> "Misdirected Request"
    429 -> "Too Many Requests"
    431 -> "Request Header Fields Too Large"
    500 -> "Internal Server Error"
    503 -> "Service Unavailable"
    else -> "Status"
}
