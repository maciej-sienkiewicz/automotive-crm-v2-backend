package pl.detailing.crm.metrics.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.security.MessageDigest
import java.time.Instant

/**
 * Guards everything under `/api/internal/metrics` — the only surface in the system that
 * deliberately reads **across tenants**.
 *
 * Design rationale
 * ────────────────
 * The rest of the CRM is row-isolated by `studio_id` and authenticated by a Spring
 * Session cookie belonging to a studio user. A platform-operator console fits neither:
 * there is no studio to scope to, and no operator user exists in the `users` table.
 * Rather than inventing a super-user role inside the tenant identity model — which
 * would mean one bug away from a studio user reading every competitor's numbers — the
 * platform surface is authenticated by a separate shared secret and is expected to be
 * additionally restricted at the reverse proxy (VPN / IP allow-list).
 *
 * Fail-closed: no configured key ⇒ 503 for every call. An analytics console is never
 * worth defaulting to "open".
 */
@Component
class PlatformAccessInterceptor(
    private val properties: MetricsProperties,
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val HEADER_PLATFORM_KEY = "X-Platform-Key"
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val configured = properties.platformApiKey

        if (configured.isBlank()) {
            log.warn(
                "Odrzucono wywołanie {} {} — crm.metrics.platform-api-key nie jest ustawiony",
                request.method, request.requestURI
            )
            respond(
                response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "PLATFORM_METRICS_DISABLED",
                "Konsola metryk platformy nie została skonfigurowana"
            )
            return false
        }

        val presented = request.getHeader(HEADER_PLATFORM_KEY)
        if (presented == null || !constantTimeEquals(presented, configured)) {
            log.warn(
                "Odrzucono wywołanie {} {} — nieprawidłowy {}",
                request.method, request.requestURI, HEADER_PLATFORM_KEY
            )
            respond(
                response, HttpServletResponse.SC_UNAUTHORIZED,
                "PLATFORM_KEY_INVALID",
                "Brak lub nieprawidłowy klucz dostępu do metryk platformy"
            )
            return false
        }

        return true
    }

    /**
     * Compares hashes rather than the raw strings: `String.equals` returns on the first
     * differing byte, which leaks the shared secret one character at a time to anyone
     * willing to measure. Hashing first makes the comparison length-independent too.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(
            digest.digest(a.toByteArray(Charsets.UTF_8)),
            MessageDigest.getInstance("SHA-256").digest(b.toByteArray(Charsets.UTF_8))
        )
    }

    private fun respond(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "code" to code,
                    "message" to message,
                    "timestamp" to Instant.now().toString()
                )
            )
        )
    }
}
