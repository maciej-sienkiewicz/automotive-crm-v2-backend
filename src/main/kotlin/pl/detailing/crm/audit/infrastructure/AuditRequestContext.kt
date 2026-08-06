package pl.detailing.crm.audit.infrastructure

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pl.detailing.crm.audit.domain.AuditChannel
import java.util.UUID

/**
 * Everything about the *surroundings* of an audited action that a handler should not have
 * to pass by hand: which surface the call came from, the caller's IP, and an id shared by
 * all events produced during one request.
 */
data class AuditRequestInfo(
    val channel: AuditChannel,
    val ipAddress: String?,
    val correlationId: UUID?
)

/**
 * Reads the ambient HTTP request to describe where an audited action came from.
 *
 * Must be called on the request thread — [RequestContextHolder] is a thread local and is
 * deliberately not inheritable, so it is empty inside `withContext(Dispatchers.IO)`. The
 * audit service captures this before switching dispatchers.
 *
 * Returns null when there is no ambient request, which happens both for genuinely
 * request-less work (a scheduled job) and for a handler that had already moved onto
 * another dispatcher before logging. The caller cannot tell the two apart, so it falls
 * back on the actor type rather than guessing here — see `AuditService.defaultChannelFor`.
 * Call sites where the surface actually matters (the customer-facing Visit Card) pass the
 * channel explicitly instead of relying on detection.
 */
object AuditRequestContext {

    private const val CORRELATION_ATTRIBUTE = "pl.detailing.crm.audit.correlationId"

    private val FORWARDED_HEADERS = listOf("X-Forwarded-For", "X-Real-IP", "CF-Connecting-IP")

    fun capture(): AuditRequestInfo? {
        val request = currentRequest() ?: return null
        return AuditRequestInfo(
            channel = channelOf(request),
            ipAddress = clientIpOf(request),
            correlationId = correlationIdOf(request)
        )
    }

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    /**
     * Derived from the URI rather than a header, because the surfaces are already separated
     * by path in [pl.detailing.crm.config.SecurityConfig] — each one authenticates differently.
     */
    private fun channelOf(request: HttpServletRequest): AuditChannel {
        val uri = request.requestURI ?: return AuditChannel.WEB_PANEL
        return when {
            uri.startsWith("/api/public/") -> AuditChannel.PUBLIC_LINK
            uri.startsWith("/api/mobile/") -> AuditChannel.MOBILE
            uri.startsWith("/api/tablet/") -> AuditChannel.TABLET
            uri.startsWith("/api/v1/inbound/") || uri.startsWith("/api/sms/inbound") -> AuditChannel.API
            else -> AuditChannel.WEB_PANEL
        }
    }

    /** First hop of the forwarding chain; falls back to the socket address. */
    private fun clientIpOf(request: HttpServletRequest): String? {
        FORWARDED_HEADERS.forEach { header ->
            val value = request.getHeader(header)
            if (!value.isNullOrBlank()) {
                val first = value.substringBefore(',').trim()
                if (first.isNotEmpty()) return first.take(45)
            }
        }
        return request.remoteAddr?.takeIf { it.isNotBlank() }?.take(45)
    }

    /**
     * One id per request, so a gesture that fans out into several entries (saving a visit
     * writes the visit, its services and its documents) can be collapsed into a single row
     * in the feed instead of flooding it.
     */
    private fun correlationIdOf(request: HttpServletRequest): UUID {
        (request.getAttribute(CORRELATION_ATTRIBUTE) as? UUID)?.let { return it }
        val generated = UUID.randomUUID()
        request.setAttribute(CORRELATION_ATTRIBUTE, generated)
        return generated
    }
}
