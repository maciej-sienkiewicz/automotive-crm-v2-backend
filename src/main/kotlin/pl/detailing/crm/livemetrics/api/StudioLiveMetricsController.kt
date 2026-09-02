package pl.detailing.crm.livemetrics.api

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.livemetrics.store.LiveMetricsKeys
import pl.detailing.crm.livemetrics.stream.LiveMetricsBroadcaster
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.time.Instant

/**
 * Metryki na żywo dla zalogowanego studia. Zakres jest ZAWSZE wyprowadzany z sesji —
 * żaden parametr nie pozwala wskazać innego tenanta.
 *
 * Kanały push: SSE poniżej albo STOMP `/topic/studio.{studioId}.metrics`.
 */
@RestController
@RequestMapping("/api/v1/live-metrics")
@RequiresPermission(Permission.STATISTICS_VIEW)
class StudioLiveMetricsController(
    private val query: LiveMetricsQueryService,
    private val broadcaster: LiveMetricsBroadcaster
) {
    private fun scope(): String = LiveMetricsKeys.tenantScope(SecurityContextHelper.getCurrentStudioId().value)

    @GetMapping("/overview")
    fun overview(): LiveMetricsOverview = query.tenantOverview(SecurityContextHelper.getCurrentStudioId().value)

    @GetMapping("/series")
    fun series(
        @RequestParam series: String,
        @RequestParam(defaultValue = "minute") bucket: String,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?
    ): SeriesResponse {
        val end = to ?: Instant.now()
        val start = from ?: defaultFrom(bucket, end)
        return query.series(scope(), series, bucket, start, end)
    }

    @GetMapping("/hour-profile")
    fun hourProfile(@RequestParam series: String, @RequestParam(defaultValue = "7") days: Int): HourProfileResponse =
        query.hourProfile(scope(), series, days)

    @GetMapping("/events")
    fun events(@RequestParam(defaultValue = "50") limit: Int): List<BusinessEventDto> = query.recent(scope(), limit)

    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter = broadcaster.subscribeTenant(SecurityContextHelper.getCurrentStudioId().value)

    companion object {
        fun defaultFrom(bucket: String, end: Instant): Instant = when (bucket.lowercase()) {
            "hour" -> end.minusSeconds(24 * 3600)
            "day" -> end.minusSeconds(30L * 24 * 3600)
            else -> end.minusSeconds(60 * 60)
        }
    }
}
