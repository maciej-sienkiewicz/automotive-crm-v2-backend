package pl.detailing.crm.instagram.analytics

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.util.*

/**
 * API v2 analityki konkurencji. Kontrakt: każda metryka jako [MetricTriple]
 * (wartość + delta + benchmark) – frontend nie pokazuje liczb bez kontekstu.
 */
@RequiresPermission(Permission.MARKETING_MANAGE)
@RestController
@RequestMapping("/api/v1/instagram")
class InstagramAnalyticsController(
    private val readService: InstagramAnalyticsReadService
) {

    @GetMapping("/overview")
    fun overview(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<OverviewResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.overview(principal.studioId, weeks.coerceIn(4, 52)))
    }

    @GetMapping("/benchmark")
    fun benchmark(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<BenchmarkResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.benchmark(principal.studioId, weeks.coerceIn(4, 52)))
    }

    @GetMapping("/content")
    fun content(
        @RequestParam(defaultValue = "12") weeks: Int,
        @RequestParam(defaultValue = "engagement") sort: String,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) format: String?,
        @RequestParam(required = false) profileId: String?,
        @RequestParam(defaultValue = "false") promoOnly: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") pageSize: Int
    ): ResponseEntity<ContentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(
            readService.content(
                studioId = principal.studioId,
                weeks = weeks.coerceIn(4, 52),
                sort = sort,
                topic = topic?.takeIf { it.isNotBlank() },
                format = format?.takeIf { it.isNotBlank() },
                profileId = profileId?.takeIf { it.isNotBlank() },
                promoOnly = promoOnly,
                page = page.coerceAtLeast(0),
                pageSize = pageSize.coerceIn(1, 60)
            )
        )
    }

    @GetMapping("/content/heatmap")
    fun heatmap(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<HeatmapResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.heatmap(principal.studioId, weeks.coerceIn(4, 52)))
    }

    @GetMapping("/hashtags")
    fun hashtags(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<HashtagsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.hashtags(principal.studioId, weeks.coerceIn(4, 52)))
    }

    @GetMapping("/insights")
    fun insights(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<InsightsListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.listInsights(principal.studioId, status?.takeIf { it.isNotBlank() }, limit))
    }

    @PostMapping("/insights/{id}/dismiss")
    fun dismissInsight(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        readService.dismissInsight(principal.studioId, UUID.fromString(id))
        ResponseEntity.noContent().build()
    }
}
