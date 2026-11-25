package io.nativeforge.core.remote.http

import io.nativeforge.core.common.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLSocket

/** What a [HttpHandler] answers: a response, a streamed file, or a WebSocket upgrade. */
sealed interface HttpReply

class Respond(val response: HttpResponse) : HttpReply

/** Streams [length] bytes from [open] (downloads); never compressed or buffered whole. */
class RespondStream(
    val status: Int,
    val contentType: String,
    val length: Long,
    val headers: Map<String, String>,
    val open: () -> InputStream,
) : HttpReply

class Upgrade(val listener: WebSocketListener, val headers: Map<String, String> = emptyMap()) : HttpReply

fun interface HttpHandler {
    /** Called on a worker thread. [HttpResponse.headers] may be extended by [decorate]. */
    fun handle(request: HttpRequest): HttpReply
}

/** Server limits and timeouts. */
data class HttpServerOptions(
    val maxConnections: Int = 48,
    val maxHeaderBytes: Int = 16 * 1024,
    val maxBodyBytes: Int = 32 * 1024 * 1024,
    val maxWebSocketMessageBytes: Int = 8 * 1024 * 1024,
    /** Time allowed for the TLS handshake and for each request to arrive. */
    val requestTimeoutMillis: Int = 20_000,
    /** How long a keep-alive connection may stay idle between requests. */
    val keepAliveMillis: Int = 75_000,
    /** Read timeout of WebSockets; the server pings more often than this. */
    val webSocketTimeoutMillis: Int = 120_000,
)

/**
 * A small HTTP/1.1 server with keep-alive, gzip and WebSocket upgrades, on blocking sockets
 * (one worker thread per connection, at most [HttpServerOptions.maxConnections]).
 *
 * [serverSocket] decides the transport: an `SSLServerSocket` gives HTTPS. [acceptPeer] runs
 * before anything is read from a connection, so refused peers never reach the parser.
 * [decorate] adds headers every response carries (security headers).
 */
class HttpServer(
    private val serverSocket: ServerSocket,
    private val handler: HttpHandler,
    private val logger: Logger,
    private val options: HttpServerOptions = HttpServerOptions(),
    private val acceptPeer: (InetAddress) -> Boolean = { true },
    private val decorate: (HttpRequest?, MutableMap<String, String>) -> Unit = { _, _ -> },
) {
    private val slots = Semaphore(options.maxConnections)
    private val threadIds = AtomicInteger()
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "nf-http-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }
    private val openSockets = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var running = false

    val port: Int get() = serverSocket.localPort

    fun start() {
        running = true
        Thread(::acceptLoop, "nf-http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { serverSocket.close() }
        openSockets.toList().forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (running) logger.warn(TAG, "accept failed", e)
                break
            }
            val peer = socket.inetAddress
            if (!acceptPeer(peer)) {
                logger.warn(TAG, "Refused connection from ${peer.hostAddress} (not on a local network)", null)
                runCatching { socket.close() }
                continue
            }
            if (!slots.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            openSockets += socket
            workers.execute {
                try {
                    serve(socket)
                } finally {
                    openSockets -= socket
                    runCatching { socket.close() }
                    slots.release()
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = options.requestTimeoutMillis
            socket.tcpNoDelay = true
            (socket as? SSLSocket)?.startHandshake()
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            var first = true
            while (running) {
                if (!first) socket.soTimeout = options.keepAliveMillis
                first = false
                val request = try {
                    HttpParser.read(input, socket.inetAddress, options.maxHeaderBytes, options.maxBodyBytes) ?: return
                } catch (e: HttpProtocolException) {
                    writeResponse(output, null, HttpResponse.error(e.status, e.message ?: "Bad request"), keepAlive = false)
                    return
                }
                socket.soTimeout = options.requestTimeoutMillis
                val reply = try {
                    handler.handle(request)
                } catch (e: Exception) {
                    logger.error(TAG, "Handler failed for ${request.method} ${request.path}", e)
                    Respond(HttpResponse.error(500, "Internal error"))
                }
                // A refused WebSocket upgrade ends the connection: the client will not reuse it.
                val keepAlive = request.keepAlive && !request.isWebSocketUpgrade
                when (reply) {
                    is Respond -> writeResponse(output, request, reply.response, keepAlive)
                    is RespondStream -> writeStream(output, request, reply)
                    is Upgrade -> {
                        upgrade(socket, input, output, request, reply)
                        return
                    }
                }
                if (!keepAlive) return
            }
        } catch (e: SocketException) {
            // Peer went away.
        } catch (e: IOException) {
            // Timeout, TLS failure (for example plain HTTP on the HTTPS port) or EOF.
        }
    }

    private fun writeResponse(output: OutputStream, request: HttpRequest?, response: HttpResponse, keepAlive: Boolean) {
        val headers = LinkedHashMap(response.headers)
        decorate(request, headers)
        var body = response.body
        if (request != null && shouldCompress(request, response, headers)) {
            body = gzip(body)
            headers["Content-Encoding"] = "gzip"
            headers["Vary"] = "Accept-Encoding"
        }
        response.contentType?.let { headers["Content-Type"] = it }
        headers["Content-Length"] = body.size.toString()
        headers["Connection"] = if (keepAlive) "keep-alive" else "close"
        writeHead(output, response.status, headers)
        if (request?.method != "HEAD" && response.status != 304 && response.status != 204) output.write(body)
        output.flush()
    }

    private fun writeStream(output: OutputStream, request: HttpRequest, reply: RespondStream) {
        val headers = LinkedHashMap(reply.headers)
        decorate(request, headers)
        headers["Content-Type"] = reply.contentType
        headers["Content-Length"] = reply.length.toString()
        headers["Connection"] = if (request.keepAlive) "keep-alive" else "close"
        writeHead(output, reply.status, headers)
        if (request.method != "HEAD") {
            reply.open().use { input ->
                var remaining = reply.length
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("File shrank while sending")
                    output.write(buffer, 0, n)
                    remaining -= n
                }
            }
        }
        output.flush()
    }

    private fun upgrade(socket: Socket, input: InputStream, output: OutputStream, request: HttpRequest, reply: Upgrade) {
        val key = request.header("sec-websocket-key")
        if (request.header("sec-websocket-version") != "13" || !WebSocket.isValidKey(key)) {
            writeResponse(output, request, HttpResponse.error(400, "Bad WebSocket handshake"), keepAlive = false)
            return
        }
        val headers = LinkedHashMap(reply.headers)
        decorate(request, headers)
        headers["Upgrade"] = "websocket"
        headers["Connection"] = "Upgrade"
        headers["Sec-WebSocket-Accept"] = WebSocket.acceptKey(key!!)
        writeHead(output, 101, headers)
        output.flush()
        socket.soTimeout = options.webSocketTimeoutMillis
        WebSocket(socket, input, output, options.maxWebSocketMessageBytes).run(reply.listener)
    }

    private fun writeHead(output: OutputStream, status: Int, headers: Map<String, String>) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
        for ((name, value) in headers) {
            // Header injection guard: values come partly from file names and user input.
            require(name.none { it == '\r' || it == '\n' || it == ':' } && value.none { it == '\r' || it == '\n' }) { "Bad header" }
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        output.write(head.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun shouldCompress(request: HttpRequest, response: HttpResponse, headers: Map<String, String>): Boolean {
        if (headers.containsKey("Content-Encoding") || response.body.size < MIN_GZIP_BYTES) return false
        if (request.header("accept-encoding")?.contains("gzip") != true) return false
        return isCompressible(response.contentType)
    }

    companion object {
        private const val TAG = "HttpServer"
        private const val MIN_GZIP_BYTES = 1024

        fun isCompressible(contentType: String?): Boolean {
            val type = contentType?.substringBefore(';')?.trim() ?: return false
            return type.startsWith("text/") || type in setOf("application/json", "application/javascript", "image/svg+xml", "application/wasm")
        }

        /**
         * Compresses [data]. Responses use the default [level] (fast enough per request);
         * static files are compressed once, so they use the best one.
         */
        fun gzip(data: ByteArray, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
            val out = ByteArrayOutputStream(data.size / 3 + 64)
            object : GZIPOutputStream(out) {
                init { def.setLevel(level) }
            }.use { it.write(data) }
            return out.toByteArray()
        }
    }
}
