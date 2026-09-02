package pl.detailing.crm.livemetrics.api

import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import java.time.Instant
import java.util.UUID

/** Zdarzenie w postaci wysyłanej na dashboardy (WebSocket / SSE / lista „ostatnie”). */
data class BusinessEventDto(
    val id: UUID,
    val tenantId: UUID,
    val type: BusinessEventType,
    val series: List<String>,
    val dimension: String?,
    val dimensionValue: String?,
    val occurredAt: Instant,
    val attributes: Map<String, String>
) {
    companion object {
        fun from(event: BusinessEvent) = BusinessEventDto(
            id = event.id,
            tenantId = event.tenantId.value,
            type = event.type,
            series = event.series(),
            dimension = event.type.dimension,
            dimensionValue = event.dimensionValue,
            occurredAt = event.occurredAt,
            attributes = event.attributes
        )
    }
}

/** Ramka wysyłana na `/topic/studio.{id}.metrics` i strumieniem SSE. */
data class LiveMetricsFrame(
    val kind: String,
    val event: BusinessEventDto? = null,
    val timestamp: Instant = Instant.now()
) {
    companion object {
        const val KIND_EVENT = "BUSINESS_EVENT"
        const val KIND_HEARTBEAT = "HEARTBEAT"
    }
}

data class SeriesPoint(val at: Instant, val count: Long)

data class SeriesDescriptor(
    val series: String,
    val type: BusinessEventType,
    val label: String,
    val dimension: String?,
    val dimensionValue: String?
)

data class SeriesStats(
    val series: String,
    val total: Long,
    val today: Long,
    val lastHour: Long,
    val last15Minutes: Long,
    val lastEventAt: Instant?
)

data class LiveMetricsOverview(
    val scope: String,
    val tenantId: UUID?,
    val zone: String,
    val generatedAt: Instant,
    val descriptors: List<SeriesDescriptor>,
    val stats: List<SeriesStats>,
    /** Ostatnie 60 minut, per seria bazowa i pod-seria. */
    val lastHourByMinute: Map<String, List<SeriesPoint>>,
    /** Ostatnie 24 godziny, per seria. */
    val last24hByHour: Map<String, List<SeriesPoint>>,
    /** Ostatnie 30 dni, per seria. */
    val last30dByDay: Map<String, List<SeriesPoint>>,
    /** Rozkład godzinowy (0–23) z ostatnich 7 dni — „o których godzinach klienci rezerwują”. */
    val hourOfDayProfile7d: Map<String, List<Long>>,
    val recentEvents: List<BusinessEventDto>
)

data class SeriesResponse(
    val scope: String,
    val series: String,
    val bucket: String,
    val from: Instant,
    val to: Instant,
    val points: List<SeriesPoint>
)

data class HourProfileResponse(
    val scope: String,
    val series: String,
    val days: Int,
    val zone: String,
    val counts: List<Long>
)

data class TenantRow(
    val tenantId: UUID,
    val name: String?,
    val today: Map<String, Long>,
    val total: Long,
    val lastEventAt: Instant?
)

data class PlatformOverview(
    val generatedAt: Instant,
    val zone: String,
    val tenantsSeen: Int,
    val platform: LiveMetricsOverview,
    val tenants: List<TenantRow>,
    val pipeline: PipelineStats
)

data class PipelineStats(
    val queued: Int,
    val queueCapacity: Int,
    val accepted: Long,
    val written: Long,
    val dropped: Long,
    val failedBatches: Long,
    val broadcast: Long,
    val sseSubscribers: Int
)
