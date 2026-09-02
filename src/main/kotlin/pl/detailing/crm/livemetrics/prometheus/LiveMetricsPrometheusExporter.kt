package pl.detailing.crm.livemetrics.prometheus

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.domain.BusinessEvent
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Most między live-metrics a Grafaną: wystawia zdarzenia biznesowe jako metryki
 * Prometheus na `/actuator/prometheus`.
 *
 *  - `crm_business_events_total{tenant_id, tenant, type, dimension}` — licznik
 *    inkrementowany przez ingest **tej instancji** (Prometheus sumuje instancje);
 *  - `crm_business_events_today{tenant_id, tenant, type}` — liczba od północy
 *    (strefa studia), odświeżana z Redisa; identyczna na każdej instancji, więc w
 *    Grafanie agregujemy `max by (tenant_id)`, nie `sum`;
 *  - `crm_business_events_last_hour{...}` — j.w., ostatnie 60 minut;
 *  - `crm_business_events_hour_of_day{tenant_id, tenant, type, hour}` — profil
 *    „o której klienci rezerwują” z ostatnich 7 dni; per tenant tylko dla rezerwacji,
 *    dla całej platformy (`tenant_id="_platform"`) dla wszystkich typów — świadomy
 *    limit kardynalności (500 tenantów × 24 h = 12k serii, nie 60k);
 *  - `crm_live_metrics_pipeline_*` — stan potoku ingestu tej instancji.
 *
 * Kardynalność jest zamknięta z założenia: etykiety to tenant (dziesiątki–setki),
 * typ (5), wymiar (max 4) i godzina (24). Żadnych id encji.
 */
@Component
class LiveMetricsPrometheusExporter(
    private val registry: MeterRegistry,
    private val store: LiveMetricsStore,
    private val worker: BusinessEventIngestWorker,
    private val broadcaster: LiveMetricsBroadcaster,
    private val studioRepository: StudioRepository,
    private val properties: LiveMetricsProperties
) {
    private val log = LoggerFactory.getLogger(LiveMetricsPrometheusExporter::class.java)

    companion object {
        const val EVENTS = "crm.business.events"
        const val TODAY = "crm.business.events.today"
        const val LAST_HOUR = "crm.business.events.last_hour"
        const val HOUR_OF_DAY = "crm.business.events.hour_of_day"
        const val PLATFORM_TENANT = "_platform"
        const val NO_DIMENSION = "none"
        const val HOUR_PROFILE_DAYS = 7
    }

    private val counters = ConcurrentHashMap<String, Counter>()
    private val tenantNames = ConcurrentHashMap<UUID, String>()
    private lateinit var todayGauge: MultiGauge
    private lateinit var lastHourGauge: MultiGauge
    private lateinit var hourOfDayGauge: MultiGauge

    @PostConstruct
    fun register() {
        todayGauge = MultiGauge.builder(TODAY).description("Zdarzenia biznesowe od północy (strefa studia)").register(registry)
        lastHourGauge = MultiGauge.builder(LAST_HOUR).description("Zdarzenia biznesowe z ostatnich 60 minut").register(registry)
        hourOfDayGauge = MultiGauge.builder(HOUR_OF_DAY).description("Rozkład godzinowy zdarzeń z ostatnich 7 dni").register(registry)
        Gauge.builder("crm.live_metrics.pipeline.queued") { worker.queued() }.register(registry)
        Gauge.builder("crm.live_metrics.pipeline.queue_capacity") { worker.capacity() }.register(registry)
        Gauge.builder("crm.live_metrics.pipeline.accepted") { worker.accepted.get() }.register(registry)
        Gauge.builder("crm.live_metrics.pipeline.written") { worker.written.get() }.register(registry)
        Gauge.builder("crm.live_metrics.pipeline.dropped") { worker.dropped.get() }.register(registry)
        Gauge.builder("crm.live_metrics.pipeline.failed_batches") { worker.failedBatches.get() }.register(registry)
        Gauge.builder("crm.live_metrics.pipeline.broadcast") { broadcaster.broadcast.get() }.register(registry)
        Gauge.builder("crm.live_metrics.sse.subscribers") { broadcaster.sseSubscribers() }.register(registry)
    }

    /** Wołane przez ingest po udanym zapisie partii — licznik rośnie tylko za realnie zapisane zdarzenia. */
    fun count(events: List<BusinessEvent>) {
        for (e in events) {
            val dim = e.dimensionValue ?: NO_DIMENSION
            val key = "${e.tenantId.value}|${e.type.name}|$dim"
            counters.computeIfAbsent(key) {
                Counter.builder(EVENTS)
                    .description("Zdarzenia biznesowe zapisane przez tę instancję")
                    .tags(tenantTags(e.tenantId.value).and("type", e.type.name).and("dimension", dim))
                    .register(registry)
            }.increment()
        }
    }

    @Scheduled(fixedDelayString = "\${crm.live-metrics.prometheus-refresh-seconds:15}000", initialDelay = 10_000)
    fun refreshGauges() {
        if (!properties.enabled) return
        try {
            val tenants = store.tenants().toList()
            refreshTenantNames(tenants)
            val now = Instant.now()
            val today = LocalDate.now(store.zone)
            val baseSeries = BusinessEventType.entries.map { it.series }
            val scopes = tenants.map { LiveMetricsKeys.tenantScope(it) }
            val todayCounts = store.dayCounts(scopes, baseSeries, today)

            val todayRows = ArrayList<MultiGauge.Row<Number>>()
            val lastHourRows = ArrayList<MultiGauge.Row<Number>>()
            val hourRows = ArrayList<MultiGauge.Row<Number>>()

            tenants.forEachIndexed { idx, tenant ->
                val scope = scopes[idx]
                val tags = tenantTags(tenant)
                for (type in BusinessEventType.entries) {
                    val t = tags.and("type", type.name)
                    todayRows += MultiGauge.Row.of(t, todayCounts[scope]?.get(type.series) ?: 0L)
                    val lastHour = store.minuteSeries(scope, type.series, now.minus(59, ChronoUnit.MINUTES), now).sumOf { it.count }
                    lastHourRows += MultiGauge.Row.of(t, lastHour)
                }
                store.hourOfDayProfile(scope, BusinessEventType.RESERVATION_CREATED.series, HOUR_PROFILE_DAYS).forEachIndexed { h, c ->
                    hourRows += MultiGauge.Row.of(tags.and("type", BusinessEventType.RESERVATION_CREATED.name).and("hour", "%02d".format(h)), c)
                }
            }
            val platformTags = Tags.of("tenant_id", PLATFORM_TENANT, "tenant", "Cała platforma")
            for (type in BusinessEventType.entries) {
                store.hourOfDayProfile(LiveMetricsKeys.PLATFORM_SCOPE, type.series, HOUR_PROFILE_DAYS).forEachIndexed { h, c ->
                    hourRows += MultiGauge.Row.of(platformTags.and("type", type.name).and("hour", "%02d".format(h)), c)
                }
            }
            todayGauge.register(todayRows, true)
            lastHourGauge.register(lastHourRows, true)
            hourOfDayGauge.register(hourRows, true)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] Prometheus gauge refresh failed: {}", e.toString())
        }
    }

    private fun refreshTenantNames(tenants: List<UUID>) {
        val missing = tenants.filter { !tenantNames.containsKey(it) }
        if (missing.isEmpty()) return
        runCatching { studioRepository.findAllById(missing).forEach { tenantNames[it.id] = it.name } }
    }

    private fun tenantTags(tenantId: UUID): Tags {
        val name = tenantNames[tenantId] ?: runCatching { studioRepository.findById(tenantId).orElse(null)?.name }.getOrNull()
            ?.also { tenantNames[tenantId] = it }
        return Tags.of("tenant_id", tenantId.toString(), "tenant", name ?: tenantId.toString().take(8))
    }
}
