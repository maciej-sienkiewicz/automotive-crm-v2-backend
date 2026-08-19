package pl.detailing.crm.metrics.apiaudit

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.session.SessionActivityTracker
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes every request that reaches a Spring handler and books it against the endpoint
 * catalog. Also doubles as the passive session signal.
 *
 * ## Why an interceptor and not the existing AOP aspect
 *
 * `ApiMetricsAspect` instruments `@RestController` methods for Prometheus and is the right
 * tool for latency histograms. It cannot serve this purpose, because a `HandlerInterceptor`
 * receives the resolved [HandlerMethod] and the matched URI template directly from Spring —
 * which is what lets a recorded call be tied to a *catalog row* rather than to a string that
 * has to be re-derived and kept in sync. The two coexist: Prometheus keeps the real-time
 * technical view, this keeps the long-horizon "is this endpoint alive" record that a
 * 15-day Prometheus retention can never answer.
 *
 * Everything here is wrapped: an audit failure must never turn a working request into a 500.
 */
@Component
class ApiUsageTrackingInterceptor(
    private val buffer: ApiUsageBuffer,
    private val catalog: EndpointCatalogCache,
    private val sessionTracker: SessionActivityTracker,
    private val properties: MetricsProperties
) : HandlerInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val ATTR_START = "crm.metrics.startNanos"
    }

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (properties.enabled) request.setAttribute(ATTR_START, System.nanoTime())
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        if (!properties.enabled) return

        try {
            if (handler !is HandlerMethod) return

            val pathTemplate = resolveTemplate(request) ?: return
            if (isIgnored(pathTemplate)) return

            val endpoint = catalog.resolve(request.method, pathTemplate, handler) ?: return

            val startNanos = request.getAttribute(ATTR_START) as? Long
            val durationMs = startNanos?.let { (System.nanoTime() - it) / 1_000_000 } ?: 0L

            val principal = SecurityContextHolder.getContext().authentication as? UserPrincipal

            buffer.record(
                endpointId = endpoint.id,
                module = endpoint.module,
                date = MetricsClock.dateOf(Instant.now()),
                studioId = principal?.studioId?.value,
                durationMs = durationMs,
                // 4xx counts as an error for the *endpoint's* health: an endpoint that only
                // ever answers 400 is as broken as one that answers 500, and treating it as
                // healthy traffic is how a dead contract stays "in use" for a year.
                isError = response.status >= 400
            )

            // Passive session signal — see SessionActivityTracker.touchFromRequest.
            if (principal != null) {
                request.getSession(false)?.id?.let { sessionId ->
                    sessionTracker.touchFromRequest(
                        principal.studioId.value,
                        principal.userId.value,
                        sessionId
                    )
                }
            }
        } catch (t: Exception) {
            log.debug("Audyt API pominął żądanie {} {}: {}", request.method, request.requestURI, t.message)
        }
    }

    private fun resolveTemplate(request: HttpServletRequest): String? =
        request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()

    private fun isIgnored(path: String): Boolean =
        properties.apiAudit.ignoredPathPrefixes.any { path.startsWith(it) }
}

/**
 * Maps a (method, path) pair to its catalog row id without touching the database on the
 * request path.
 *
 * The catalog is written once per boot and read on every request; a database lookup per
 * request would put a SELECT in front of every CRM operation to answer a question whose
 * answer cannot change while the process runs.
 *
 * A miss (a route registered after boot, or one the registrar failed on) is recorded as a
 * negative entry and skipped rather than retried — one absent row must not become a query
 * storm.
 */
@Component
class EndpointCatalogCache(
    private val repository: pl.detailing.crm.metrics.infrastructure.ApiEndpointRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The only two catalog facts the hot path needs. */
    data class CatalogEntry(val id: UUID, val module: String)

    private val cache = ConcurrentHashMap<String, CatalogEntry>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    fun resolve(httpMethod: String, pathTemplate: String, handler: HandlerMethod): CatalogEntry? {
        val key = "$httpMethod $pathTemplate"

        cache[key]?.let { return it }
        if (key in misses) return null

        return try {
            val row = repository.findBySignature(httpMethod, pathTemplate)
                ?: repository.findBySignature("ANY", pathTemplate)

            if (row == null) {
                misses.add(key)
                log.debug("Brak wpisu katalogu dla {} (handler {})", key, handler.method.name)
                null
            } else {
                val entry = CatalogEntry(row.id, row.module)
                cache[key] = entry
                entry
            }
        } catch (ex: Exception) {
            log.debug("Nie udało się rozwiązać endpointu {}: {}", key, ex.message)
            null
        }
    }

    /** Called after the registrar runs so routes registered late get a second chance. */
    fun invalidate() {
        cache.clear()
        misses.clear()
    }

    fun size(): Int = cache.size
}
