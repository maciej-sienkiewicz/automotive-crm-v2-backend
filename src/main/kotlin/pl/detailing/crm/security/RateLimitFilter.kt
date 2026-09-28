package pl.detailing.crm.security

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.detailing.crm.auth.UserPrincipal
import java.time.Instant

/**
 * Redis-backed fixed-window rate limiter applied at the servlet filter level, before the
 * Spring Security filter chain processes the request.
 *
 * Buckets:
 *  - login  : 10 requests / 10 minutes per IP  (brute-force / credential-stuffing protection)
 *  - signup : 5 requests  / 60 minutes per IP  (account-creation abuse)
 *  - anonymous API : 300 requests / 60 seconds per IP
 *  - signed-in API : 900 requests / 60 seconds per USER, plus 3000 / 60 seconds per IP
 *
 * Dlaczego zalogowany ruch liczy się per użytkownik, a nie per IP: całe studio siedzi za
 * jednym routerem - komputer w biurze, tablet na hali, telefony w wifi. Wszyscy dzielili
 * jeden licznik 300 żądań na minutę, a moduł poczty sam potrafi zużyć jego sporą część
 * (pierwsza synchronizacja skrzynki, lista wątków, podglądy), więc „Przekroczono limit
 * żądań" dostawał ten, kto akurat kliknął po kimś innym. Limit IP dla zalogowanych
 * zostaje, ale jako sufit na nadużycie, a nie budżet pracy biura.
 *
 * Użytkownik jest brany z sesji już zweryfikowanej przez Spring Session (filtr stoi po
 * [SessionRepositoryFilter]) - samo ciasteczko z losową wartością nie daje nowego licznika,
 * bo nie ma za nim sesji w Redisie i taki ruch liczy się jako anonimowy, per IP.
 *
 * The real client IP is resolved by [ClientIpResolver]: forwarded-IP headers are honoured
 * only when the request arrived through a trusted proxy, otherwise the socket peer counts.
 *
 * Responses include X-RateLimit-Limit and X-RateLimit-Remaining headers so frontends
 * can implement back-off without polling; a 429 also carries Retry-After.
 */
@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 10)
class RateLimitFilter(
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry,
    /**
     * Extra proxy ranges (CIDR, comma-separated) whose forwarded-IP headers we honour on
     * top of loopback / private networks. See [ClientIpResolver] for the trust model.
     */
    @Value("\${security.rate-limit.trusted-proxies:}") trustedProxies: String
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)
    private val clientIpResolver = ClientIpResolver(trustedProxies.split(","))

    internal data class Bucket(val keyPrefix: String, val limit: Int, val windowSeconds: Long)

    private data class Hit(val count: Long, val ttlSeconds: Long)

    companion object {
        internal val LOGIN = Bucket("ratelimit:login", 10, 600)
        internal val SIGNUP = Bucket("ratelimit:signup", 5, 3600)
        internal val API = Bucket("ratelimit:api", 300, 60)
        internal val USER_API = Bucket("ratelimit:user", 900, 60)
        internal val OFFICE_API = Bucket("ratelimit:office", 3000, 60)

        /**
         * INCR i EXPIRE w jednym kroku. Dwa osobne polecenia zostawiały licznik bez czasu
         * życia, gdy drugie nie doszło (restart, timeout) - wtedy adres był zablokowany na
         * stałe, a nie na minutę. Skrypt dokłada też TTL licznikowi, który go nie ma.
         */
        private val INCREMENT = DefaultRedisScript(
            """
            local count = redis.call('INCR', KEYS[1])
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
            """.trimIndent(),
            List::class.java
        )
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain
    ) {
        val ip = clientIpResolver.resolve(request)
        val path = request.requestURI

        val checks: List<Pair<Bucket, String>> = when {
            path.contains("/auth/login") -> listOf(LOGIN to ip)
            path.contains("/auth/signup") -> listOf(SIGNUP to ip)
            else -> signedInUser(request)
                ?.let { userId -> listOf(USER_API to userId, OFFICE_API to ip) }
                ?: listOf(API to ip)
        }

        var reported = false
        for ((bucket, subject) in checks) {
            // Redis niedostępny: przepuszczamy. Limit chroni przed nadużyciem, a jego awaria
            // nie może wyłączyć całej aplikacji odpowiedziami 500.
            val hit = hit(bucket, subject) ?: continue
            if (!reported) {
                response.setHeader("X-RateLimit-Limit", bucket.limit.toString())
                response.setHeader("X-RateLimit-Remaining", maxOf(0L, bucket.limit - hit.count).toString())
                reported = true
            }
            if (hit.count > bucket.limit) {
                meterRegistry.counter(
                    "crm.security.rate_limit.exceeded",
                    "path", normalizePath(path),
                    "bucket", bucket.keyPrefix.substringAfter(':')
                ).increment()
                reject(response, hit.ttlSeconds)
                return
            }
        }

        chain.doFilter(request, response)
    }

    /**
     * Id zalogowanego użytkownika z sesji, bez tworzenia nowej. Sesja jest już wczytana
     * przez SessionRepositoryFilter i Spring Security weźmie ją z tego samego miejsca.
     */
    private fun signedInUser(request: HttpServletRequest): String? = try {
        val context = request.getSession(false)
            ?.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as? SecurityContext
        (context?.authentication as? UserPrincipal)?.userId?.value?.toString()
    } catch (e: Exception) {
        null
    }

    private fun hit(bucket: Bucket, subject: String): Hit? = try {
        val result = redisTemplate.execute(INCREMENT, listOf("${bucket.keyPrefix}:$subject"), bucket.windowSeconds.toString())
        val count = (result?.getOrNull(0) as? Number)?.toLong() ?: 1L
        val ttl = (result?.getOrNull(1) as? Number)?.toLong() ?: bucket.windowSeconds
        Hit(count, ttl)
    } catch (e: Exception) {
        log.warn("Rate limit check skipped, Redis unavailable: {}", e.message)
        null
    }

    private fun reject(response: HttpServletResponse, retryAfterSeconds: Long) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        // Retry-After: po tylu sekundach okno się kończy - front czeka tyle, zamiast ponawiać od razu.
        response.setHeader("Retry-After", maxOf(1L, retryAfterSeconds).toString())
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            """{"error":"Zbyt wiele żądań","message":"Przekroczono limit żądań. Spróbuj za chwilę.","timestamp":"${Instant.now()}"}"""
        )
    }

    private fun normalizePath(uri: String): String =
        uri.split("/").joinToString("/") { seg ->
            if (seg.matches(Regex("[0-9a-fA-F-]{36}"))) "{id}" else seg
        }
}
