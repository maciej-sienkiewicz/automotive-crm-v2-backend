package pl.detailing.crm.inbound.email.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Validates the shared secret on all requests to the CloudFlare email webhook endpoint.
 * Uses constant-time comparison via MessageDigest.isEqual to prevent timing-based attacks.
 *
 * CloudFlare must include the token in the X-Cloudflare-Email-Token header on every request.
 */
@Component
class CloudflareWebhookTokenFilter(
    @Value("\${cloudflare.email.webhook.secret-token:}") private val secretToken: String
) : OncePerRequestFilter() {

    companion object {
        const val WEBHOOK_PATH = "/api/v1/inbound/email"
        const val TOKEN_HEADER = "X-Cloudflare-Email-Token"
        private val log = LoggerFactory.getLogger(CloudflareWebhookTokenFilter::class.java)
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.equals(WEBHOOK_PATH, ignoreCase = true)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        if (secretToken.isBlank()) {
            log.error("[INBOUND_EMAIL_SECURITY] cloudflare.email.webhook.secret-token is not configured — rejecting all requests to {}", WEBHOOK_PATH)
            writeUnauthorized(response, "Webhook token not configured on server")
            return
        }

        val providedToken = request.getHeader(TOKEN_HEADER)
        if (providedToken.isNullOrBlank() || !tokensMatch(providedToken, secretToken)) {
            log.warn("[INBOUND_EMAIL_SECURITY] Invalid or missing {} header from ip={}", TOKEN_HEADER, request.remoteAddr)
            writeUnauthorized(response, "Unauthorized")
            return
        }

        chain.doFilter(request, response)
    }

    // Constant-time comparison to prevent timing attacks on the secret token
    private fun tokensMatch(provided: String, expected: String): Boolean =
        MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    private fun writeUnauthorized(response: HttpServletResponse, message: String) {
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"error":"$message"}""")
    }
}
