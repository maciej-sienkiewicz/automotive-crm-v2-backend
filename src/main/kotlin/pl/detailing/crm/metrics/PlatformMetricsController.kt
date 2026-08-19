package pl.detailing.crm.metrics

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.metrics.apiaudit.EndpointCatalogCache
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.infrastructure.ApiEndpointRepository
import pl.detailing.crm.metrics.query.*
import pl.detailing.crm.metrics.rollup.DailyPlatformRollupJob
import pl.detailing.crm.metrics.rollup.DailyStudioRollupJob
import pl.detailing.crm.shared.NotFoundException
import java.time.LocalDate
import java.util.UUID

/**
 * The platform-operator console API — the deliberate exception to this system's
 * row-level tenant isolation.
 *
 * Access is a shared secret checked by [pl.detailing.crm.metrics.config.PlatformAccessInterceptor],
 * separate from the tenant identity model entirely: inventing a super-user *inside* the
 * `users` table would put every studio one authorisation bug away from reading its
 * competitors' revenue. This surface is expected to sit behind a VPN or IP allow-list too.
 *
 * Mounted under `/api/internal` rather than `/api/v1` so the boundary is visible in the path,
 * in access logs and in any reverse-proxy rule anyone writes later.
 */
@RestController
@RequestMapping("/api/internal/metrics")
class PlatformMetricsController(
    private val overviewHandler: GetPlatformOverviewHandler,
    private val tenantHandler: GetTenantMetricsHandler,
    private val sessionHandler: GetSessionAnalyticsHandler,
    private val deadEndpointHandler: GetDeadEndpointsHandler,
    private val errorHandler: GetErrorAnalyticsHandler,
    private val healthHandler: GetTenantHealthHandler,
    private val studioRollup: DailyStudioRollupJob,
    private val platformRollup: DailyPlatformRollupJob,
    private val endpointRepository: ApiEndpointRepository,
    private val catalogCache: EndpointCatalogCache
) {

    // ── Requirement 1: subscriptions, live ───────────────────────────────────

    @GetMapping("/overview")
    fun overview(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?
    ): ResponseEntity<PlatformOverviewResponse> =
        ResponseEntity.ok(overviewHandler.handle(date ?: MetricsClock.today()))

    @GetMapping("/trend")
    fun trend(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?
    ): ResponseEntity<List<Map<String, Any?>>> {
        val range = resolveRange(from, to, defaultDays = 90)
        return ResponseEntity.ok(overviewHandler.trend(range.first, range.second))
    }

    // ── Requirement 2: time spent, split by owner vs employee ────────────────

    @GetMapping("/sessions")
    fun sessions(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "20") topN: Int
    ): ResponseEntity<SessionAnalyticsResponse> {
        val range = resolveRange(from, to, defaultDays = 30)
        return ResponseEntity.ok(sessionHandler.handle(range.first, range.second, topN))
    }

    // ── Requirement 3: dead endpoints ────────────────────────────────────────

    @GetMapping("/api-audit")
    fun apiAudit(
        @RequestParam(defaultValue = "false") includeRemovedFromCode: Boolean
    ): ResponseEntity<DeadEndpointReport> =
        ResponseEntity.ok(deadEndpointHandler.handle(includeRemovedFromCode))

    /**
     * Marks an endpoint as intentionally low-traffic so it stops surfacing as a deletion
     * candidate. Without this the same twelve false positives reappear in every report
     * until people stop reading it — the failure mode of every static-analysis tool.
     */
    @PatchMapping("/api-audit/{endpointId}/exempt")
    fun exemptEndpoint(
        @PathVariable endpointId: UUID,
        @RequestBody body: ExemptEndpointRequest
    ): ResponseEntity<Void> {
        val endpoint = endpointRepository.findById(endpointId).orElseThrow {
            NotFoundException("Nie znaleziono endpointu $endpointId")
        }
        endpoint.isRetentionExempt = body.exempt
        endpoint.exemptionNote = body.note?.take(300)
        endpointRepository.save(endpoint)
        return ResponseEntity.noContent().build()
    }

    // ── Requirements 4 & 5, plus adoption and consumption, per tenant ────────

    @GetMapping("/tenants/{studioId}")
    fun tenant(
        @PathVariable studioId: UUID,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?
    ): ResponseEntity<TenantMetricsResponse> {
        val range = resolveRange(from, to, defaultDays = 30)
        return ResponseEntity.ok(tenantHandler.handle(studioId, range.first, range.second))
    }

    // ── Requirement 6: tenant-aware error tracking ───────────────────────────

    @GetMapping("/errors")
    fun errorGroups(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "100") limit: Int
    ): ResponseEntity<List<ErrorGroupRow>> =
        ResponseEntity.ok(errorHandler.listGroups(status, limit))

    @GetMapping("/errors/{fingerprint}")
    fun errorDetail(
        @PathVariable fingerprint: String,
        @RequestParam(defaultValue = "20") occurrences: Int
    ): ResponseEntity<ErrorGroupDetail> =
        ResponseEntity.ok(errorHandler.groupDetail(fingerprint, occurrences))

    @PatchMapping("/errors/{fingerprint}")
    fun updateErrorGroup(
        @PathVariable fingerprint: String,
        @RequestBody body: UpdateErrorGroupRequest
    ): ResponseEntity<ErrorGroupRow> =
        ResponseEntity.ok(errorHandler.updateStatus(fingerprint, body))

    /** "Customer X is on the phone — what have they been hitting?" */
    @GetMapping("/tenants/{studioId}/errors")
    fun tenantErrors(
        @PathVariable studioId: UUID,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<List<ErrorGroupRow>> =
        ResponseEntity.ok(errorHandler.errorsForStudio(studioId, limit))

    // ── Retention board ──────────────────────────────────────────────────────

    @GetMapping("/health")
    fun tenantHealth(
        @RequestParam(required = false) risk: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate?
    ): ResponseEntity<List<TenantHealthRow>> =
        ResponseEntity.ok(healthHandler.handle(date ?: MetricsClock.today(), risk))

    // ── Operations ───────────────────────────────────────────────────────────

    /**
     * Recomputes a day's aggregates on demand — after a bug fix, a backfill, or a
     * scheduler outage. Safe to call repeatedly: the roll-ups recompute from source rather
     * than incrementing, so a re-run converges instead of doubling.
     */
    @PostMapping("/recompute")
    fun recompute(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate
    ): ResponseEntity<Map<String, Any>> {
        studioRollup.rollupFor(date)
        platformRollup.rollupFor(date)
        return ResponseEntity.ok(mapOf("recomputed" to date.toString(), "status" to "OK"))
    }

    /** Clears the endpoint-resolution cache after a catalog change. */
    @PostMapping("/api-audit/refresh-cache")
    fun refreshCatalogCache(): ResponseEntity<Void> {
        catalogCache.invalidate()
        return ResponseEntity.noContent().build()
    }

    /**
     * Ranges default to a trailing window rather than "all history": an unbounded default
     * on an analytics endpoint is a table scan waiting for the first person who forgets a
     * query parameter.
     */
    private fun resolveRange(from: LocalDate?, to: LocalDate?, defaultDays: Long): Pair<LocalDate, LocalDate> {
        val end = to ?: MetricsClock.today()
        val start = from ?: end.minusDays(defaultDays)
        return if (start.isAfter(end)) end to start else start to end
    }
}

data class ExemptEndpointRequest(
    val exempt: Boolean,
    val note: String? = null
)
