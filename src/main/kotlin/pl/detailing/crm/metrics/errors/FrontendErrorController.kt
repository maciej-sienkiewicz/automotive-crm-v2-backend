package pl.detailing.crm.metrics.errors

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ErrorSeverity
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives errors the browser caught, and attributes them to the tenant whose session
 * reported them.
 *
 * ## Why the tenant comes from the session, never from the body
 *
 * The request is authenticated, so the studio is already known from the principal. Taking
 * it from the payload instead would let any authenticated user attribute failures to any
 * competitor on the platform — and these numbers feed support priorities and SLA
 * conversations. The client cannot choose whose data it writes.
 *
 * ## Rate limiting
 *
 * A render loop in the frontend can emit thousands of identical errors per minute. The
 * per-session cap turns that into a bounded signal instead of a self-inflicted write flood
 * — the tenth report of the same defect adds nothing the first one did not.
 *
 * Frontend integration:
 * ```js
 * window.addEventListener('error', e => report(e.error, 'ERROR'));
 * window.addEventListener('unhandledrejection', e => report(e.reason, 'ERROR'));
 * // React: componentDidCatch(error, info) => report(error, 'CRITICAL', info.componentStack)
 * ```
 */
@RestController
@RequestMapping("/api/v1/metrics/errors")
class FrontendErrorController(
    private val errorTrackingService: ErrorTrackingService,
    private val properties: MetricsProperties
) {

    private val rateLimiter = ConcurrentHashMap<String, RateWindow>()

    @PostMapping
    fun report(
        @Valid @RequestBody request: FrontendErrorRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val sessionId = httpRequest.getSession(false)?.id ?: principal.userId.toString()

        // 202 rather than 429 when throttled: the client cannot act on the rejection and
        // must not retry, and a red row in the browser console for a *metrics* call is
        // itself a support ticket waiting to happen.
        if (!allow(sessionId)) return ResponseEntity.accepted().build()

        errorTrackingService.recordFrontendError(
            exceptionClass = request.name.take(200),
            message = request.message,
            stackTrace = request.stack,
            studioId = principal.studioId,
            userId = principal.userId,
            route = request.route,
            appVersion = request.appVersion,
            userAgent = httpRequest.getHeader("User-Agent"),
            correlationId = request.correlationId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
            severity = runCatching { ErrorSeverity.valueOf(request.severity) }.getOrDefault(ErrorSeverity.ERROR),
            context = buildMap {
                request.componentStack?.let { put("componentStack", it.take(2000)) }
                request.url?.let { put("url", it.take(500)) }
                request.browser?.let { put("browser", it.take(120)) }
                request.extra?.forEach { (k, v) -> put(k.take(50), v?.toString()?.take(500)) }
            }.ifEmpty { null }
        )

        return ResponseEntity.accepted().build()
    }

    private fun allow(sessionId: String): Boolean {
        val now = Instant.now().epochSecond / 60
        val window = rateLimiter.compute(sessionId) { _, existing ->
            if (existing == null || existing.minute != now) RateWindow(now) else existing
        }!!

        // Bounded map: without this the limiter itself becomes the memory leak it exists
        // to prevent. Sessions are short-lived, so a periodic wipe costs at most one
        // minute's accounting.
        if (rateLimiter.size > 10_000) rateLimiter.clear()

        return window.count.incrementAndGet() <= properties.errors.frontendRateLimitPerMinute
    }

    private class RateWindow(val minute: Long) {
        val count = AtomicInteger(0)
    }
}

data class FrontendErrorRequest(
    /** Error class or name, e.g. `TypeError`, `ChunkLoadError`. */
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,

    @field:Size(max = 1000)
    val message: String? = null,

    /** Stack trace, ideally source-mapped. Truncated server-side. */
    @field:Size(max = 20_000)
    val stack: String? = null,

    /** SPA route where it happened, e.g. `/wizyty/{id}/protokol`. */
    @field:Size(max = 300)
    val route: String? = null,

    @field:Size(max = 500)
    val url: String? = null,

    /** WARNING | ERROR | CRITICAL. Anything else falls back to ERROR. */
    @field:Size(max = 20)
    val severity: String = "ERROR",

    @field:Size(max = 40)
    val appVersion: String? = null,

    /**
     * The `X-Correlation-ID` of the backend call that triggered this, if any — joins a
     * frontend crash to the server request behind it on one timeline.
     */
    @field:Size(max = 40)
    val correlationId: String? = null,

    @field:Size(max = 2000)
    val componentStack: String? = null,

    @field:Size(max = 120)
    val browser: String? = null,

    /** Small, free-form context. Values are stringified and truncated. */
    val extra: Map<String, Any?>? = null
)
