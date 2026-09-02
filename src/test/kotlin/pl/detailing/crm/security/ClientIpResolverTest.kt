package pl.detailing.crm.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Rate-limit bypass: `CF-Connecting-IP` / `X-Forwarded-For` were trusted from anyone,
 * so a client sending a fresh header per request had unlimited login / PIN / demo
 * attempts. Headers may only count when the socket peer is a trusted proxy.
 */
class ClientIpResolverTest {

    private val resolver = ClientIpResolver(trustedProxyCidrs = listOf("203.0.113.0/24", "2001:db8::/32"))

    private fun request(peer: String, vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply {
            remoteAddr = peer
            headers.forEach { (k, v) -> addHeader(k, v) }
        }

    @Test
    fun `spoofed CF-Connecting-IP from a public peer is ignored - the peer is the client`() {
        val req = request("198.51.100.7", "CF-Connecting-IP" to "10.0.0.${(1..254).random()}")
        assertEquals("198.51.100.7", resolver.resolve(req))
    }

    @Test
    fun `spoofed X-Forwarded-For from a public peer is ignored`() {
        val req = request("198.51.100.7", "X-Forwarded-For" to "1.2.3.4, 5.6.7.8")
        assertEquals("198.51.100.7", resolver.resolve(req))
    }

    @Test
    fun `header from a private-network proxy (docker, nginx) is honoured`() {
        val req = request("172.18.0.2", "CF-Connecting-IP" to "198.51.100.9")
        assertEquals("198.51.100.9", resolver.resolve(req))
    }

    @Test
    fun `X-Forwarded-For - the LAST hop (appended by the trusted proxy) wins, not the client-chosen first`() {
        val req = request("127.0.0.1", "X-Forwarded-For" to "6.6.6.6, 198.51.100.9")
        assertEquals("198.51.100.9", resolver.resolve(req))
    }

    @Test
    fun `configured CIDR ranges are trusted, neighbours are not`() {
        assertTrue(resolver.isTrustedProxy("203.0.113.200"))
        assertFalse(resolver.isTrustedProxy("203.0.114.1"))
        assertTrue(resolver.isTrustedProxy("2001:db8:1::5"))
        assertFalse(resolver.isTrustedProxy("2001:db9::1"))
    }

    @Test
    fun `loopback, RFC1918 and unique-local IPv6 are trusted by default`() {
        listOf("127.0.0.1", "::1", "10.1.2.3", "192.168.1.10", "172.16.5.5", "fd12::1").forEach {
            assertTrue(resolver.isTrustedProxy(it), it)
        }
        assertFalse(resolver.isTrustedProxy("8.8.8.8"))
    }

    @Test
    fun `garbage remote address never throws`() {
        val req = request("not-an-ip", "CF-Connecting-IP" to "1.1.1.1")
        assertEquals("not-an-ip", resolver.resolve(req))
    }
}
