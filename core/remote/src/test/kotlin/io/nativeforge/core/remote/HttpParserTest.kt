package io.nativeforge.core.remote

import io.nativeforge.core.remote.http.HttpParser
import io.nativeforge.core.remote.http.HttpProtocolException
import io.nativeforge.core.remote.http.WebSocket
import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpParserTest {
    private val local = InetAddress.getLoopbackAddress()

    private fun parse(raw: String, maxBody: Int = 1024) =
        HttpParser.read(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)), local, 4096, maxBody)

    @Test
    fun `parses method, decoded path, query, headers, cookies and body`() {
        val request = parse(
            "PUT /api/file%20x?project=a+b&path=src%2Fmain.c HTTP/1.1\r\nHost: 192.168.0.2:8443\r\n" +
                "Cookie: a=1; __Host-nf_session=tok\r\nContent-Length: 5\r\n\r\nhello",
        )!!
        assertEquals("PUT", request.method)
        assertEquals("/api/file x", request.path)
        assertEquals("a b", request.query["project"])
        assertEquals("src/main.c", request.query["path"])
        assertEquals("192.168.0.2:8443", request.header("Host"))
        assertEquals("tok", request.cookie("__Host-nf_session"))
        assertEquals("hello", request.bodyText())
    }

    @Test
    fun `plus in a path stays a plus`() {
        assertEquals("/a+b", parse("GET /a+b HTTP/1.1\r\nHost: x\r\n\r\n")!!.path)
    }

    @Test
    fun `returns null at a clean end of stream`() {
        assertNull(parse(""))
    }

    @Test
    fun `refuses smuggling and oversized requests`() {
        fun status(raw: String) = assertFailsWith<HttpProtocolException> { parse(raw) }.status
        assertEquals(400, status("GET / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab"))
        assertEquals(400, status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))
        assertEquals(411, status("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"))
        assertEquals(431, status("GET / HTTP/1.1\r\nX: ${"a".repeat(5000)}\r\n\r\n"))
        assertEquals(400, status("GET http://evil/ HTTP/1.1\r\n\r\n"))
        assertEquals(405, status("TRACE / HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("GET /%00 HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `websocket handshake key follows RFC 6455`() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
        assertTrue(WebSocket.isValidKey("dGhlIHNhbXBsZSBub25jZQ=="))
        assertTrue(!WebSocket.isValidKey("short"))
    }
}
