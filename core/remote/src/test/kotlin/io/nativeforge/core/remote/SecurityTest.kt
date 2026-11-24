package io.nativeforge.core.remote

import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.remote.security.NetworkPolicy
import io.nativeforge.core.remote.security.PairingResult
import io.nativeforge.core.remote.security.TlsIdentity
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityTest {
    private val dir = Files.createTempDirectory("nf-remote").toFile()

    @Test
    fun `only local network peers are accepted`() {
        fun ok(ip: String) = NetworkPolicy.isLocalPeer(InetAddress.getByName(ip))
        listOf("127.0.0.1", "10.0.0.5", "172.16.3.4", "172.31.255.1", "192.168.1.20", "169.254.1.1", "100.64.0.1", "100.127.255.254", "::1", "fd12:3456::1", "fe80::1", "::ffff:192.168.1.2")
            .forEach { assertTrue(ok(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "2001:4860::8888", "::ffff:8.8.8.8", "1.1.1.1")
            .forEach { assertFalse(ok(it), it) }
    }

    @Test
    fun `host header must be an address, localhost, mdns or tailscale name`() {
        listOf("192.168.0.4:8443", "[fe80::1]:8443", "localhost:8443", "phone.local", "pixel.tail1234.ts.net:8443", "10.0.0.1")
            .forEach { assertTrue(NetworkPolicy.isAllowedHost(it), it) }
        listOf(null, "", "evil.com", "evil.com:8443", "192.168.0.4.evil.com", "a@b", "fe80::1", "[fe80::1]x", "1.2.3.4:abc")
            .forEach { assertFalse(NetworkPolicy.isAllowedHost(it), it.toString()) }
    }

    @Test
    fun `same origin requires the exact https origin`() {
        assertTrue(NetworkPolicy.isSameOrigin("https://192.168.0.4:8443", "192.168.0.4:8443"))
        assertFalse(NetworkPolicy.isSameOrigin("http://192.168.0.4:8443", "192.168.0.4:8443"))
        assertFalse(NetworkPolicy.isSameOrigin("https://evil.com", "192.168.0.4:8443"))
        assertFalse(NetworkPolicy.isSameOrigin(null, "192.168.0.4:8443"))
    }

    @Test
    fun `pairing code works once and only the token hash is stored`() {
        val store = File(dir, "browsers.json")
        val access = AccessControl(store)
        val code = access.newPairingCode()
        assertEquals(8, code.code.length)
        assertTrue(code.code.all { it in AccessControl.ALPHABET })
        val paired = assertIs<PairingResult.Paired>(access.pair(AccessControl.formatCode(code.code).lowercase(), "Firefox on Linux"))
        assertNotNull(access.authenticate(paired.token))
        assertIs<PairingResult.NoActiveCode>(access.pair(code.code, "again"))
        val saved = store.readText()
        assertFalse(paired.token in saved, "the token itself must never be written")
        assertTrue(AccessControl.hash(paired.token) in saved)
        // Survives a restart.
        assertEquals("Firefox on Linux", AccessControl(store).authenticate(paired.token)?.label)
    }

    @Test
    fun `wrong codes destroy the code after the limit`() {
        val access = AccessControl(null, maxFailures = 3)
        val code = access.newPairingCode().code
        val wrong = if (code == "22222222") "33333333" else "22222222"
        assertIs<PairingResult.WrongCode>(access.pair(wrong, "x"))
        assertIs<PairingResult.WrongCode>(access.pair(wrong, "x"))
        assertIs<PairingResult.TooManyAttempts>(access.pair(wrong, "x"))
        assertIs<PairingResult.NoActiveCode>(access.pair(code, "x"))
    }

    @Test
    fun `codes expire and sessions lapse after the lifetime without use`() {
        var now = 1_000_000L
        val access = AccessControl(null, clock = { now }, codeLifetimeMillis = 1000, sessionLifetimeMillis = 5000)
        val code = access.newPairingCode().code
        now += 1001
        assertNull(access.currentPairingCode())
        assertIs<PairingResult.NoActiveCode>(access.pair(code, "x"))
        val token = (access.pair(access.newPairingCode().code, "x") as PairingResult.Paired).token
        now += 4000
        assertNotNull(access.authenticate(token)) // use refreshes the session
        now += 4000
        assertNotNull(access.authenticate(token))
        now += 5001
        assertNull(access.authenticate(token))
    }

    @Test
    fun `revoking and signing out invalidate tokens`() {
        val access = AccessControl(null)
        val a = access.pair(access.newPairingCode().code, "A") as PairingResult.Paired
        val b = access.pair(access.newPairingCode().code, "B") as PairingResult.Paired
        access.revoke(a.browser.id)
        assertNull(access.authenticate(a.token))
        assertNotNull(access.authenticate(b.token))
        access.logout(b.token)
        assertNull(access.authenticate(b.token))
        assertNull(access.authenticate(null))
        assertNull(access.authenticate("x".repeat(500)))
    }

    @Test
    fun `tls identity is stored and reused, with a stable fingerprint`() {
        val file = File(dir, "tls.p12")
        val first = TlsIdentity.loadOrCreate(file)
        val second = TlsIdentity.loadOrCreate(file)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(32, first.fingerprint.split(':').size)
        assertTrue(first.certificate.sigAlgName.contains("ECDSA", ignoreCase = true))
        TlsIdentity.reset(file)
        assertTrue(TlsIdentity.loadOrCreate(file).fingerprint != first.fingerprint)
    }
}
