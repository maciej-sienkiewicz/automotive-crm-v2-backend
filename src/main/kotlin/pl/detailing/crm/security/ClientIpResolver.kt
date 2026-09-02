package pl.detailing.crm.security

import jakarta.servlet.http.HttpServletRequest
import java.net.InetAddress

/**
 * Resolves the originating client IP for rate limiting and lockouts.
 *
 * `CF-Connecting-IP` / `X-Forwarded-For` are plain request headers: any client can send
 * them. They are only meaningful when the request reached us THROUGH a proxy we trust —
 * one that overwrites (Cloudflare) or appends (nginx) them. Honouring them from an
 * arbitrary peer meant a random header value per request turned every limit into
 * "unlimited": credential stuffing, PIN guessing, demo-tenant flooding.
 *
 * Trust model:
 *  - `remoteAddr` is a loopback / private / link-local address (a reverse proxy or
 *    Cloudflare tunnel on the same host or docker network) → headers are trusted,
 *  - `remoteAddr` is inside one of the configured CIDR ranges → trusted,
 *  - anything else → the socket peer is the client, headers are ignored.
 *
 * For `X-Forwarded-For` the LAST entry wins: that is the one appended by the trusted
 * proxy; the first entry is whatever the client chose to send.
 */
class ClientIpResolver(trustedProxyCidrs: Collection<String>) {

    private data class Cidr(val network: ByteArray, val prefixBits: Int)

    private val cidrs: List<Cidr> = trustedProxyCidrs
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map(::parseCidr)

    fun resolve(request: HttpServletRequest): String {
        val peer = request.remoteAddr ?: "unknown"
        if (!isTrustedProxy(peer)) return peer

        request.getHeader("CF-Connecting-IP")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        request.getHeader("X-Forwarded-For")
            ?.split(",")
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() }
            ?.let { return it }
        return peer
    }

    fun isTrustedProxy(address: String): Boolean {
        val inet = runCatching { InetAddress.getByName(address) }.getOrNull() ?: return false
        if (inet.isLoopbackAddress || inet.isSiteLocalAddress || inet.isLinkLocalAddress) return true
        if (isUniqueLocalIpv6(inet)) return true
        val bytes = inet.address
        return cidrs.any { it.matches(bytes) }
    }

    /** fc00::/7 — the IPv6 counterpart of RFC 1918, not covered by isSiteLocalAddress. */
    private fun isUniqueLocalIpv6(inet: InetAddress): Boolean {
        val b = inet.address
        return b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC
    }

    private fun Cidr.matches(candidate: ByteArray): Boolean {
        if (candidate.size != network.size) return false
        var bits = prefixBits
        for (i in network.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((candidate[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    private fun parseCidr(spec: String): Cidr {
        val (host, prefix) = spec.split("/", limit = 2).let { parts ->
            val addr = InetAddress.getByName(parts[0])
            val bits = parts.getOrNull(1)?.toInt() ?: (addr.address.size * 8)
            addr.address to bits
        }
        require(prefix in 0..(host.size * 8)) { "Nieprawidłowy prefiks CIDR: $spec" }
        return Cidr(host, prefix)
    }
}
