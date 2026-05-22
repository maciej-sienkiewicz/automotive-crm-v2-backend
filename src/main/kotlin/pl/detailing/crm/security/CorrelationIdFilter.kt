package pl.detailing.crm.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.detailing.crm.auth.UserPrincipal
import java.util.UUID

/**
 * Injects a correlation ID and request-scoped security context into MDC so every
 * log line emitted during request processing carries traceability fields.
 *
 * Ordered at -50 to run after Spring Security (-100), ensuring the SecurityContext
 * is already populated when we enrich MDC with user identity.
 *
 * Fields added to MDC:
 *  - correlationId  : UUID taken from X-Correlation-ID header or generated fresh
 *  - remoteIp       : real client IP, honouring Cloudflare's CF-Connecting-IP header
 *  - requestPath    : raw URI
 *  - requestMethod  : HTTP verb
 *  - studioId       : tenant UUID (only when authenticated)
 *  - userId         : user UUID (only when authenticated)
 *  - userRole       : OWNER / MANAGER / DETAILER (only when authenticated)
 */
@Component
@Order(-50)
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        const val HEADER_CORRELATION_ID = "X-Correlation-ID"
        const val MDC_CORRELATION_ID = "correlationId"
        const val MDC_REMOTE_IP = "remoteIp"
        const val MDC_STUDIO_ID = "studioId"
        const val MDC_USER_ID = "userId"
        const val MDC_USER_ROLE = "userRole"
        const val MDC_REQUEST_PATH = "requestPath"
        const val MDC_REQUEST_METHOD = "requestMethod"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val correlationId = request.getHeader(HEADER_CORRELATION_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        try {
            MDC.put(MDC_CORRELATION_ID, correlationId)
            MDC.put(MDC_REMOTE_IP, resolveClientIp(request))
            MDC.put(MDC_REQUEST_PATH, request.requestURI)
            MDC.put(MDC_REQUEST_METHOD, request.method)

            val principal = SecurityContextHolder.getContext().authentication as? UserPrincipal
            if (principal != null) {
                MDC.put(MDC_STUDIO_ID, principal.studioId.toString())
                MDC.put(MDC_USER_ID, principal.userId.toString())
                MDC.put(MDC_USER_ROLE, principal.role.name)
            }

            response.addHeader(HEADER_CORRELATION_ID, correlationId)
            chain.doFilter(request, response)
        } finally {
            MDC.clear()
        }
    }

    private fun resolveClientIp(request: HttpServletRequest): String =
        request.getHeader("CF-Connecting-IP")
            ?: request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr
}
