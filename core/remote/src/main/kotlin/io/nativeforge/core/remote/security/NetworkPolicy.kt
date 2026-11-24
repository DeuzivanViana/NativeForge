package io.nativeforge.core.remote.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Which peers and host names the server talks to.
 *
 * Peers: by default only loopback, private LAN ranges (10/8, 172.16/12, 192.168/16),
 * link-local, IPv6 unique-local, and 100.64/10 (Tailscale and other VPN overlays). A phone
 * on mobile data with a public address is therefore never reachable from the internet, even
 * if a carrier forwarded the port.
 *
 * Host header: only IP literals, `localhost`, mDNS `.local` names and Tailscale `.ts.net`
 * names. This defeats DNS rebinding, where a web page on another domain resolves its own
 * name to the phone's address to get around the browser's same-origin rule.
 */
object NetworkPolicy {

    fun isLocalPeer(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> isCgnat(bytes)
            is Inet6Address -> {
                // fc00::/7 unique local addresses.
                if (bytes[0].toInt() and 0xFE == 0xFC) return true
                // IPv4-mapped (::ffff:a.b.c.d): judge the embedded IPv4 address.
                val mapped = bytes.take(10).all { it.toInt() == 0 } && bytes[10].toInt() == -1 && bytes[11].toInt() == -1
                if (mapped) isLocalPeer(InetAddress.getByAddress(bytes.copyOfRange(12, 16))) else false
            }
            else -> false
        }
    }

    private fun isCgnat(b: ByteArray) = (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64

    /** True when a request's Host header names this server in a way a rebinding attack cannot. */
    fun isAllowedHost(hostHeader: String?): Boolean {
        val host = hostOf(hostHeader ?: return false)?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".ts.net")) return true
        return isIpLiteral(host)
    }

    /** The host part of a Host header (without port and IPv6 brackets), or null if malformed. */
    fun hostOf(hostHeader: String): String? {
        val value = hostHeader.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() || it == '/' || it == '@' }) return null
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end < 0) return null
            val rest = value.substring(end + 1)
            if (rest.isNotEmpty() && !(rest.startsWith(":") && rest.drop(1).all(Char::isDigit))) return null
            return value.substring(1, end)
        }
        val colon = value.lastIndexOf(':')
        if (colon >= 0 && value.indexOf(':') != colon) return null // bare IPv6 must use brackets
        if (colon >= 0 && !value.substring(colon + 1).all(Char::isDigit)) return null
        return if (colon >= 0) value.substring(0, colon) else value
    }

    fun isIpLiteral(host: String): Boolean {
        val v4 = host.split('.')
        if (v4.size == 4 && v4.all { part -> part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toInt() <= 255 }) return true
        // IPv6: hex groups and colons (optionally a zone id); no DNS lookup is ever made.
        val bare = host.substringBefore('%')
        return bare.contains(':') && bare.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    /**
     * Same-origin check for state-changing requests and WebSocket upgrades: the browser always
     * sends Origin there, and it must name this very host (blocks CSRF and cross-site WebSockets).
     */
    fun isSameOrigin(origin: String?, hostHeader: String?): Boolean {
        if (origin == null || hostHeader == null) return false
        return origin.equals("https://${hostHeader.trim()}", ignoreCase = true)
    }
}
