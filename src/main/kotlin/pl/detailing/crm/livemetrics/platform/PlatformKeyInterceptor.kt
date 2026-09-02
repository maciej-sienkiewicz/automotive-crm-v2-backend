package pl.detailing.crm.livemetrics.platform

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import java.security.MessageDigest

/**
 * Brama konsoli operatora (`/api/internal/...`): nagłówek `X-Platform-Key` porównywany
 * ze wspólnym sekretem w stałym czasie. Brak skonfigurowanego klucza = konsola
 * zamknięta (503), nigdy otwarta „bo puste równa się puste”.
 */
@Component
class PlatformKeyInterceptor(
    private val properties: LiveMetricsProperties
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(PlatformKeyInterceptor::class.java)

    companion object {
        const val HEADER = "X-Platform-Key"
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return true
        val configured = properties.platformApiKey
        if (configured.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Platform console is not configured")
            return false
        }
        val presented = request.getHeader(HEADER) ?: ""
        if (!constantTimeEquals(configured, presented)) {
            log.warn("[PLATFORM] rejected {} {} from {}", request.method, request.requestURI, request.remoteAddr)
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid platform key")
            return false
        }
        return true
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
