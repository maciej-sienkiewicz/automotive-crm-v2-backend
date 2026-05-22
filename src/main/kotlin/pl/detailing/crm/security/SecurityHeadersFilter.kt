package pl.detailing.crm.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Adds defensive HTTP security headers to every response.
 *
 * Ordered near HIGHEST_PRECEDENCE so headers are present even on responses
 * returned by the Spring Security filter chain (401/403) or error pages.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class SecurityHeadersFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        // Prevents browsers from MIME-sniffing a response away from the declared content-type
        response.addHeader("X-Content-Type-Options", "nosniff")

        // Forbids the page from being embedded in a frame (clickjacking protection)
        response.addHeader("X-Frame-Options", "DENY")

        // Controls how much referrer information is included in requests
        response.addHeader("Referrer-Policy", "strict-origin-when-cross-origin")

        // Restricts which browser APIs this origin may use
        response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

        // Instructs browsers to always use HTTPS for this domain for 1 year
        response.addHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")

        // API-only CSP: no inline scripts/styles needed, deny framing
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'none'; frame-ancestors 'none'"
        )

        chain.doFilter(request, response)
    }
}
