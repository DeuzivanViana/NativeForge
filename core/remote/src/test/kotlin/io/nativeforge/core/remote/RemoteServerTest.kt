package io.nativeforge.core.remote

import io.nativeforge.core.common.Logger
import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.remote.security.TlsIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.KeyStore
import java.util.Base64
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteServerTest {
    private val dir = Files.createTempDirectory("nf-server").toFile()
    private val identity = TlsIdentity.loadOrCreate(File(dir, "tls.p12"))
    private val access = AccessControl(File(dir, "browsers.json"))
    private val backend = FakeBackend(dir)
    private val server = RemoteServer(backend, access, SilentLogger)
    private val port: Int
    private val host get() = "127.0.0.1:$port"
    private val origin get() = "https://$host"

    init {
        val socket = identity.serverSocket(0, InetAddress.getLoopbackAddress())
        port = socket.localPort
        server.start(socket)
    }

    @AfterTest
    fun stop() = server.stop()

    class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body)
    }

    /** A raw HTTPS request that trusts exactly the server's certificate (pinning, as a careful user does). */
    private fun call(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): Reply {
        val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("s", identity.certificate) }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
        }
        (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            val all = linkedMapOf("Host" to host, "Connection" to "close", "Content-Length" to body.size.toString()) + headers
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                all.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            socket.outputStream.write(head.toByteArray() + body)
            socket.outputStream.flush()
            val input = BufferedInputStream(socket.inputStream)
            val raw = input.readBytes()
            val split = String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            val lines = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val status = lines[0].split(' ')[1].toInt()
            val map = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            var payload = raw.copyOfRange(split + 4, raw.size)
            if (map["content-encoding"] == "gzip") payload = GZIPInputStream(payload.inputStream()).readBytes()
            return Reply(status, map, payload)
        }
    }

    private fun pairedCookie(): String {
        val code = access.newPairingCode().code
        val reply = call("POST", "/api/pair", mapOf("Origin" to origin, "Content-Type" to "application/json"), """{"code":"$code"}""".toByteArray())
        assertEquals(200, reply.status, reply.text)
        val cookie = reply.headers["set-cookie"]!!
        assertTrue("HttpOnly" in cookie && "Secure" in cookie && "SameSite=Strict" in cookie && cookie.startsWith("__Host-"))
        return cookie.substringBefore(';')
    }

    @Test
    fun `page and assets are public, hardened and cached`() {
        val page = call("GET", "/", mapOf("Accept-Encoding" to "gzip"))
        assertEquals(200, page.status)
        val csp = page.headers["content-security-policy"]!!
        assertTrue("default-src 'self'" in csp && "frame-ancestors 'none'" in csp && "'sha256-" in csp, csp)
        assertEquals("nosniff", page.headers["x-content-type-options"])
        assertEquals("no-cache", page.headers["cache-control"])
        val asset = Regex("/a/[0-9a-f]+/js/app.js").find(page.text)!!.value
        val js = call("GET", asset, mapOf("Accept-Encoding" to "gzip"))
        assertEquals(200, js.status)
        assertEquals("gzip", js.headers["content-encoding"])
        assertTrue("immutable" in js.headers["cache-control"]!!)
        assertEquals(304, call("GET", asset, mapOf("If-None-Match" to js.headers["etag"]!!)).status)
        assertEquals(404, call("GET", asset.replace("js/app.js", "../index.html")).status)
    }

    @Test
    fun `api needs a paired session`() {
        assertEquals(401, call("GET", "/api/bootstrap").status)
        assertEquals(401, call("GET", "/api/bootstrap", mapOf("Cookie" to "__Host-nf_session=forged")).status)
        val cookie = pairedCookie()
        val boot = call("GET", "/api/bootstrap", mapOf("Cookie" to cookie))
        assertEquals(200, boot.status, boot.text)
        assertTrue("\"templates\"" in boot.text && "#282c34" in boot.text)
    }

    @Test
    fun `wrong host, cross origin and missing origin are refused`() {
        val cookie = pairedCookie()
        assertEquals(421, call("GET", "/api/bootstrap", mapOf("Cookie" to cookie, "Host" to "evil.example:$port")).status)
        assertEquals(403, call("POST", "/api/projects", mapOf("Cookie" to cookie, "Origin" to "https://evil.example"), "{}".toByteArray()).status)
        assertEquals(403, call("POST", "/api/projects", mapOf("Cookie" to cookie), "{}".toByteArray()).status)
        assertEquals(403, call("POST", "/api/pair", emptyMap(), """{"code":"x"}""".toByteArray()).status)
    }

    @Test
    fun `projects and files round trip, confined to the project`() {
        val cookie = pairedCookie()
        val auth = mapOf("Cookie" to cookie, "Origin" to origin)
        val created = call("POST", "/api/projects", auth, """{"name":"hello","template":"console","language":"C"}""".toByteArray())
        assertEquals(201, created.status, created.text)
        val key = "hello"
        assertEquals(200, call("PUT", "/api/file?project=$key&path=src/extra.c", auth, "int x;".toByteArray()).status)
        val modified = Regex("\"modified\":(\\d+)").find(call("GET", "/api/file?project=$key&path=src/extra.c", auth).text)!!.groupValues[1]
        val save = call("PUT", "/api/file?project=$key&path=src/extra.c&expected=$modified", auth, "int y;".toByteArray())
        assertEquals(200, save.status, save.text)
        assertEquals(409, call("PUT", "/api/file?project=$key&path=src/extra.c&expected=1", auth, "int z;".toByteArray()).status)
        val read = call("GET", "/api/file?project=$key&path=src/extra.c", auth)
        assertTrue("int y;" in read.text, read.text)
        val tree = call("GET", "/api/tree?project=$key", auth)
        assertTrue("src/extra.c" in tree.text)
        val escape = call("GET", "/api/file?project=$key&path=../browsers.json", auth)
        assertEquals(400, escape.status)
        val escape2 = call("GET", "/api/file?project=$key&path=${"%2E%2E%2Fbrowsers.json"}", auth)
        assertEquals(400, escape2.status)
        assertEquals(404, call("GET", "/api/file?project=nope&path=a.c", auth).status)
    }

    @Test
    fun `websocket upgrade needs a session and the same origin`() {
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        val ws = mapOf("Upgrade" to "websocket", "Connection" to "Upgrade", "Sec-WebSocket-Version" to "13", "Sec-WebSocket-Key" to key)
        assertEquals(401, call("GET", "/ws", ws + ("Origin" to origin)).status)
        val cookie = pairedCookie()
        assertEquals(403, call("GET", "/ws", ws + mapOf("Origin" to "https://evil.example", "Cookie" to cookie)).status)
        assertNotNull(cookie)
    }

    @Test
    fun `browser labels are short`() {
        assertEquals("Chrome on Windows", RemoteServer.browserLabel("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"))
        assertEquals("Firefox on Linux", RemoteServer.browserLabel("Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0"))
    }
}

object SilentLogger : Logger {
    override fun debug(tag: String, message: String) {}
    override fun info(tag: String, message: String) {}
    override fun warn(tag: String, message: String, error: Throwable?) {}
    override fun error(tag: String, message: String, error: Throwable?) {}
}
