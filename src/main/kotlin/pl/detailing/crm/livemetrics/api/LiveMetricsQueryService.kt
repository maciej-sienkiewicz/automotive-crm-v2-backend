package pl.detailing.crm.livemetrics.api

import org.springframework.stereotype.Service
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.ingest.BusinessEventIngestWorker
import pl.detailing.crm.livemetrics.store.LiveMetricsKeys
import pl.detailing.crm.livemetrics.store.LiveMetricsStore
import pl.detailing.crm.livemetrics.stream.LiveMetricsBroadcaster
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Read model dashboardów: składa z liczników w Redisie gotowe do narysowania serie.
 * Każde wywołanie to kilkadziesiąt HGETALL w jednym pipeline — bez bazy SQL.
 */
@Service
class LiveMetricsQueryService(
    private val store: LiveMetricsStore,
    private val worker: BusinessEventIngestWorker,
    private val broadcaster: LiveMetricsBroadcaster,
    private val studioRepository: StudioRepository
) {
    companion object {
        const val MAX_MINUTE_RANGE_MINUTES = 3L * 24 * 60
        const val MAX_HOUR_RANGE_HOURS = 90L * 24
        const val MAX_DAY_RANGE_DAYS = 400L
        const val MAX_RECENT = 200
        val DESCRIPTORS: List<SeriesDescriptor> = BusinessEventType.entries.flatMap { type ->
            listOf(SeriesDescriptor(type.series, type, type.label, null, null)) +
                type.dimensions.sorted().map { v -> SeriesDescriptor(type.subSeries(v), type, "${type.label} · ${v.lowercase().replace('_', ' ')}", type.dimension, v) }
        }
    }

    fun tenantOverview(tenantId: UUID, now: Instant = Instant.now()): LiveMetricsOverview =
        overview(LiveMetricsKeys.tenantScope(tenantId), tenantId, now)

    fun platformOverview(now: Instant = Instant.now()): PlatformOverview {
        val platform = overview(LiveMetricsKeys.PLATFORM_SCOPE, null, now)
        val tenantIds = store.tenants().toList()
        val scopes = tenantIds.map { LiveMetricsKeys.tenantScope(it) }
        val baseSeries = BusinessEventType.entries.map { it.series }
        val today = store.dayCounts(scopes, baseSeries, LocalDate.now(store.zone))
        val names = if (tenantIds.isEmpty()) emptyMap() else
            studioRepository.findAllById(tenantIds).associate { it.id to it.name }
        val rows = tenantIds.map { id ->
            val scope = LiveMetricsKeys.tenantScope(id)
            val totals = store.totals(scope)
            val last = store.lastSeen(scope).values.maxOrNull()
            TenantRow(
                tenantId = id,
                name = names[id],
                today = today[scope] ?: baseSeries.associateWith { 0L },
                total = baseSeries.sumOf { totals[it] ?: 0L },
                lastEventAt = last
            )
        }.sortedWith(compareByDescending<TenantRow> { it.lastEventAt ?: Instant.EPOCH }.thenBy { it.name ?: "" })
        return PlatformOverview(
            generatedAt = now,
            zone = store.zone.id,
            tenantsSeen = tenantIds.size,
            platform = platform,
            tenants = rows,
            pipeline = pipelineStats()
        )
    }

    fun pipelineStats() = PipelineStats(
        queued = worker.queued(),
        queueCapacity = worker.capacity(),
        accepted = worker.accepted.get(),
        written = worker.written.get(),
        dropped = worker.dropped.get(),
        failedBatches = worker.failedBatches.get(),
        broadcast = broadcaster.broadcast.get(),
        sseSubscribers = broadcaster.sseSubscribers()
    )

    fun series(scope: String, series: String, bucket: String, from: Instant, to: Instant): SeriesResponse {
        require(series in BusinessEventType.allKnownSeries()) { "Unknown series: $series" }
        require(!to.isBefore(from)) { "'to' must not be before 'from'" }
        val points = when (bucket.lowercase()) {
            "minute" -> {
                require(ChronoUnit.MINUTES.between(from, to) <= MAX_MINUTE_RANGE_MINUTES) { "Minute range too wide" }
                store.minuteSeries(scope, series, from, to)
            }
            "hour" -> {
                require(ChronoUnit.HOURS.between(from, to) <= MAX_HOUR_RANGE_HOURS) { "Hour range too wide" }
                store.hourSeries(scope, series, from, to)
            }
            "day" -> {
                require(ChronoUnit.DAYS.between(from, to) <= MAX_DAY_RANGE_DAYS) { "Day range too wide" }
                store.daySeries(scope, series, from.atZone(store.zone).toLocalDate(), to.atZone(store.zone).toLocalDate())
            }
            else -> throw IllegalArgumentException("bucket must be minute|hour|day")
        }
        return SeriesResponse(scope, series, bucket.lowercase(), from, to, points.map { SeriesPoint(it.at, it.count) })
    }

    fun hourProfile(scope: String, series: String, days: Int): HourProfileResponse {
        require(series in BusinessEventType.allKnownSeries()) { "Unknown series: $series" }
        require(days in 1..90) { "days must be 1..90" }
        return HourProfileResponse(scope, series, days, store.zone.id, store.hourOfDayProfile(scope, series, days).toList())
    }

    fun recent(scope: String, limit: Int): List<BusinessEventDto> = store.recent(scope, limit.coerceIn(1, MAX_RECENT))

    private fun overview(scope: String, tenantId: UUID?, now: Instant): LiveMetricsOverview {
        val totals = store.totals(scope)
        val lastSeen = store.lastSeen(scope)
        val today = LocalDate.now(store.zone)
        val allSeries = DESCRIPTORS.map { it.series }

        val lastHourByMinute = allSeries.associateWith { s ->
            store.minuteSeries(scope, s, now.minus(59, ChronoUnit.MINUTES), now).map { SeriesPoint(it.at, it.count) }
        }
        val last24hByHour = allSeries.associateWith { s ->
            store.hourSeries(scope, s, now.minus(23, ChronoUnit.HOURS), now).map { SeriesPoint(it.at, it.count) }
        }
        val last30dByDay = allSeries.associateWith { s ->
            store.daySeries(scope, s, today.minusDays(29), today).map { SeriesPoint(it.at, it.count) }
        }
        val profile = BusinessEventType.entries.associate { it.series to store.hourOfDayProfile(scope, it.series, 7).toList() }

        val stats = allSeries.map { s ->
            val minutes = lastHourByMinute[s] ?: emptyList()
            SeriesStats(
                series = s,
                total = totals[s] ?: 0L,
                today = last30dByDay[s]?.lastOrNull()?.count ?: 0L,
                lastHour = minutes.sumOf { it.count },
                last15Minutes = minutes.takeLast(15).sumOf { it.count },
                lastEventAt = lastSeen[s]
            )
        }
        return LiveMetricsOverview(
            scope = scope,
            tenantId = tenantId,
            zone = store.zone.id,
            generatedAt = now,
            descriptors = DESCRIPTORS,
            stats = stats,
            lastHourByMinute = lastHourByMinute,
            last24hByHour = last24hByHour,
            last30dByDay = last30dByDay,
            hourOfDayProfile7d = profile,
            recentEvents = store.recent(scope, 50)
        )
    }
}
