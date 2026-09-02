package pl.detailing.crm.livemetrics.api

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import pl.detailing.crm.livemetrics.store.LiveMetricsKeys
import pl.detailing.crm.livemetrics.stream.LiveMetricsBroadcaster
import java.time.Instant
import java.util.UUID

/**
 * Konsola operatora platformy — wszystkie tenanty. Cross-tenant z założenia, więc poza
 * modelem sesji: chroni ją `PlatformKeyInterceptor` (`X-Platform-Key`) na `/api/internal/...`.
 */
@RestController
@RequestMapping("/api/internal/live-metrics")
class PlatformLiveMetricsController(
    private val query: LiveMetricsQueryService,
    private val broadcaster: LiveMetricsBroadcaster
) {
    @GetMapping("/overview")
    fun overview(): PlatformOverview = query.platformOverview()

    @GetMapping("/tenants/{tenantId}/overview")
    fun tenantOverview(@PathVariable tenantId: UUID): LiveMetricsOverview = query.tenantOverview(tenantId)

    @GetMapping("/series")
    fun series(
        @RequestParam series: String,
        @RequestParam(defaultValue = "minute") bucket: String,
        @RequestParam(required = false) tenantId: UUID?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?
    ): SeriesResponse {
        val end = to ?: Instant.now()
        val start = from ?: StudioLiveMetricsController.defaultFrom(bucket, end)
        return query.series(scope(tenantId), series, bucket, start, end)
    }

    @GetMapping("/hour-profile")
    fun hourProfile(
        @RequestParam series: String,
        @RequestParam(defaultValue = "7") days: Int,
        @RequestParam(required = false) tenantId: UUID?
    ): HourProfileResponse = query.hourProfile(scope(tenantId), series, days)

    @GetMapping("/events")
    fun events(@RequestParam(defaultValue = "50") limit: Int, @RequestParam(required = false) tenantId: UUID?): List<BusinessEventDto> =
        query.recent(scope(tenantId), limit)

    @GetMapping("/pipeline")
    fun pipeline(): PipelineStats = query.pipelineStats()

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = broadcaster.subscribePlatform()

    private fun scope(tenantId: UUID?): String =
        tenantId?.let { LiveMetricsKeys.tenantScope(it) } ?: LiveMetricsKeys.PLATFORM_SCOPE
}
