package pl.detailing.crm.security

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.detailing.crm.observability.MetricsTags
import java.time.Duration
import java.time.Instant

/**
 * Redis-backed sliding-window rate limiter applied at the servlet filter level,
 * before the Spring Security filter chain processes the request.
 *
 * Three rate-limit buckets:
 *  - login  : 10 requests / 10 minutes per IP  (brute-force / credential-stuffing protection)
 *  - signup : 5 requests  / 60 minutes per IP  (account-creation abuse)
 *  - api    : 300 requests / 60 seconds per IP (general API abuse)
 *
 * The real client IP is resolved from the CF-Connecting-IP header (Cloudflare) or
 * X-Forwarded-For, so the rate limit applies to the originating client, not the proxy.
 *
 * Responses include X-RateLimit-Limit and X-RateLimit-Remaining headers so frontends
 * can implement back-off without polling.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class RateLimitFilter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry
) : OncePerRequestFilter() {

    private data class Bucket(val keyPrefix: String, val limit: Int, val windowSeconds: Long)

    companion object {
        private val LOGIN  = Bucket("ratelimit:login",  10, 600)
        private val SIGNUP = Bucket("ratelimit:signup",  5, 3600)
        private val API    = Bucket("ratelimit:api",   300, 60)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val ip     = resolveClientIp(request)
        val path   = request.requestURI
        val bucket = resolveBucket(path)

        val key   = "${bucket.keyPrefix}:$ip"
        val count = redisTemplate.opsForValue().increment(key) ?: 1L
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(bucket.windowSeconds))
        }

        val remaining = maxOf(0L, bucket.limit - count)
        response.addHeader("X-RateLimit-Limit", bucket.limit.toString())
        response.addHeader("X-RateLimit-Remaining", remaining.toString())

        if (count > bucket.limit) {
            meterRegistry.counter(
                MetricsTags.SECURITY_RATE_LIMIT_EXCEEDED,
                MetricsTags.TAG_PATH, normalizePath(path)
            ).increment()

            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write(
                """{"error":"Zbyt wiele żądań","message":"Przekroczono limit żądań. Spróbuj za chwilę.","timestamp":"${Instant.now()}"}"""
            )
            return
        }

        chain.doFilter(request, response)
    }

    private fun resolveBucket(path: String): Bucket = when {
        path.contains("/auth/login")  -> LOGIN
        path.contains("/auth/signup") -> SIGNUP
        else                          -> API
    }

    private fun resolveClientIp(request: HttpServletRequest): String =
        request.getHeader("CF-Connecting-IP")
            ?: request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr

    private fun normalizePath(uri: String): String =
        uri.split("/").joinToString("/") { seg ->
            if (seg.matches(Regex("[0-9a-fA-F-]{36}"))) "{id}" else seg
        }
}
