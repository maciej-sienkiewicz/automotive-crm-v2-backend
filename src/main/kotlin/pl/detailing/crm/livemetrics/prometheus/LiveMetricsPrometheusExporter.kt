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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Most między live-metrics a Grafaną: wystawia zdarzenia biznesowe jako metryki
 * Prometheus na `/actuator/prometheus`.
 *
 *  - `crm_business_events_total{tenant_id, tenant, type, dimension}` — licznik
 *    inkrementowany przez ingest **tej instancji** (Prometheus sumuje instancje);
 *  - `crm_business_events_all_time{tenant_id, tenant, type}` — suma od początku (Redis,
 *    bez TTL): jedyna metryka odpowiadająca na pytania o stan („kto ma podłączoną pocztę”),
 *    a nie o dzisiejszy ruch;
 *  - `crm_business_events_today{tenant_id, tenant, type}` — liczba od północy
 *    (strefa studia), odświeżana z Redisa; identyczna na każdej instancji, więc w
 *    Grafanie agregujemy `max by (tenant_id)`, nie `sum`;
 *  - `crm_business_events_hour_of_day{tenant_id, tenant, type, hour}` — profil
 *    „o której klienci rezerwują” z ostatnich 7 dni; per tenant tylko dla rezerwacji,
 *    dla całej platformy (`tenant_id="_platform"`) dla wszystkich typów — świadomy
 *    limit kardynalności (500 tenantów × 24 h = 12k serii, nie 60k);
 *  - `crm_live_metrics_pipeline_*` — stan potoku ingestu tej instancji.
 *
 * Kardynalność jest zamknięta z założenia: etykiety to tenant (dziesiątki–setki),
 * typ (12), wymiar (max 4) i godzina (24). Żadnych id encji. Na tenanta wychodzi ~25 serii
 * licznika, po 12 gauge'y „dziś" i „od początku" oraz 24 kubełki godzinowe (tylko rezerwacje).
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
        const val ALL_TIME = "crm.business.events.all_time"
        const val HOUR_OF_DAY = "crm.business.events.hour_of_day"
        const val PLATFORM_TENANT = "_platform"
        const val NO_DIMENSION = "none"
        const val HOUR_PROFILE_DAYS = 7
        const val HOUR_PROFILE_REFRESH_MS = 5L * 60 * 1000
        const val ALL_TIME_REFRESH_MS = 5L * 60 * 1000
    }

    private val counters = ConcurrentHashMap<String, Counter>()
    private val primedTenants = ConcurrentHashMap.newKeySet<UUID>()
    private val tenantNames = ConcurrentHashMap<UUID, String>()
    private lateinit var todayGauge: MultiGauge
    private lateinit var allTimeGauge: MultiGauge
    private lateinit var hourOfDayGauge: MultiGauge

    @PostConstruct
    fun register() {
        todayGauge = MultiGauge.builder(TODAY).description("Zdarzenia biznesowe od północy (strefa studia)").register(registry)
        allTimeGauge = MultiGauge.builder(ALL_TIME).description("Zdarzenia biznesowe od początku istnienia tenanta").register(registry)
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
            counter(e.tenantId.value, e.type, e.dimensionValue ?: NO_DIMENSION).increment()
        }
    }

    /**
     * Rejestruje z zerem wszystkie liczniki tenanta — każdy typ i każdą dozwoloną wartość wymiaru.
     *
     * Bez tego licznik powstaje dopiero przy pierwszym zdarzeniu i od razu ma wartość 1, więc seria
     * pojawia się w Prometheusie „od jedynki". `increase()` liczy przyrost MIĘDZY dwiema próbkami i
     * nie ma czego odjąć od pierwszej z nich: skok „serii nie ma → 1" jest niewidzialny, a wykresy
     * gubiły pierwsze zdarzenie każdej kombinacji (tenant, typ, wymiar) — po każdym restarcie
     * instancji od nowa, bo liczniki żyją w jej pamięci. Widoczny skok `0 → 1` wymaga, żeby zero
     * zostało wyscrape'owane WCZEŚNIEJ, czyli rejestracji z wyprzedzeniem, nie przy inkrementacji.
     *
     * Kardynalność się nie zmienia: to dokładnie te serie, które i tak by powstały (10 na tenanta).
     */
    private fun primeCounters(tenants: List<UUID>) {
        for (tenant in tenants) {
            if (!primedTenants.add(tenant)) continue
            for (type in BusinessEventType.entries) {
                val dims = if (type.dimensions.isEmpty()) setOf(NO_DIMENSION) else type.dimensions
                for (dim in dims) counter(tenant, type, dim)
            }
        }
    }

    private fun counter(tenantId: UUID, type: BusinessEventType, dim: String): Counter =
        counters.computeIfAbsent("$tenantId|${type.name}|$dim") {
            Counter.builder(EVENTS)
                .description("Zdarzenia biznesowe zapisane przez tę instancję")
                .tags(tenantTags(tenantId).and("type", type.name).and("dimension", dim))
                .register(registry)
        }

    /**
     * Liczniki „od północy" — jedna partia HGET-ów na wszystkie tenanty i typy.
     * To jedyny gauge odświeżany w tempie scrape'u, bo tylko on musi nadążać za żywym ruchem.
     */
    @Scheduled(fixedDelayString = "\${crm.live-metrics.prometheus-refresh-seconds:15}000", initialDelay = 10_000)
    fun refreshTodayGauges() {
        if (!properties.enabled) return
        try {
            val tenants = store.tenants().toList()
            refreshTenantNames(tenants)
            primeCounters(tenants)
            val scopes = tenants.map { LiveMetricsKeys.tenantScope(it) }
            val baseSeries = BusinessEventType.entries.map { it.series }
            val todayCounts = store.dayCounts(scopes, baseSeries, LocalDate.now(store.zone))

            val rows = ArrayList<MultiGauge.Row<Number>>(tenants.size * baseSeries.size)
            tenants.forEachIndexed { idx, tenant ->
                val tags = tenantTags(tenant)
                for (type in BusinessEventType.entries) {
                    rows += MultiGauge.Row.of(tags.and("type", type.name), todayCounts[scopes[idx]]?.get(type.series) ?: 0L)
                }
            }
            todayGauge.register(rows, true)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] today gauge refresh failed: {}", e.toString())
        }
    }

    /**
     * Liczniki „od początku” — jedyna odpowiedź na pytania o stan, a nie o ruch.
     *
     * „Kto skonfigurował pocztę” albo „ile profili IG dodał” to pytania o fakt, który zdarzył się
     * raz i dawno. Licznik dzienny pokazuje na nie zero u każdego, kto akurat dziś nic nie zrobił —
     * czyli u wszystkich, których pytanie dotyczy. Suma z Redisa (`lm:{scope}:total`, bez TTL)
     * odpowiada wprost i przeżywa restarty, w przeciwieństwie do `crm_business_events_total`.
     *
     * Odświeżane rzadko: to jeden HGETALL na tenanta, a odpowiedź nie zmienia się w minutę.
     * Eksportujemy wyłącznie serie bazowe — pod-serie (`VISIT_CREATED:DIRECT`) są inkrementowane
     * razem z bazową, więc trafiłyby do sumy drugi raz.
     */
    @Scheduled(fixedDelay = ALL_TIME_REFRESH_MS, initialDelay = 25_000)
    fun refreshAllTimeGauges() {
        if (!properties.enabled) return
        try {
            val tenants = store.tenants().toList()
            refreshTenantNames(tenants)
            val rows = ArrayList<MultiGauge.Row<Number>>(tenants.size * BusinessEventType.entries.size)
            for (tenant in tenants) {
                val totals = store.totals(LiveMetricsKeys.tenantScope(tenant))
                val tags = tenantTags(tenant)
                for (type in BusinessEventType.entries) {
                    rows += MultiGauge.Row.of(tags.and("type", type.name), totals[type.series] ?: 0L)
                }
            }
            allTimeGauge.register(rows, true)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] all-time gauge refresh failed: {}", e.toString())
        }
    }

    /**
     * Profil godzinowy z ostatnich 7 dni. Odświeżany rzadko, bo czyta 7 kubełków na tenanta,
     * a odpowiada na pytanie („o której klienci rezerwują"), które nie zmienia się w minutę.
     */
    @Scheduled(fixedDelay = HOUR_PROFILE_REFRESH_MS, initialDelay = 20_000)
    fun refreshHourProfileGauges() {
        if (!properties.enabled) return
        try {
            val tenants = store.tenants().toList()
            refreshTenantNames(tenants)
            val rows = ArrayList<MultiGauge.Row<Number>>()
            for (tenant in tenants) {
                val tags = tenantTags(tenant).and("type", BusinessEventType.RESERVATION_CREATED.name)
                store.hourOfDayProfile(LiveMetricsKeys.tenantScope(tenant), BusinessEventType.RESERVATION_CREATED.series, HOUR_PROFILE_DAYS)
                    .forEachIndexed { h, c -> rows += MultiGauge.Row.of(tags.and("hour", "%02d".format(h)), c) }
            }
            val platformTags = Tags.of("tenant_id", PLATFORM_TENANT, "tenant", "Cała platforma")
            for (type in BusinessEventType.entries) {
                store.hourOfDayProfile(LiveMetricsKeys.PLATFORM_SCOPE, type.series, HOUR_PROFILE_DAYS)
                    .forEachIndexed { h, c -> rows += MultiGauge.Row.of(platformTags.and("type", type.name).and("hour", "%02d".format(h)), c) }
            }
            hourOfDayGauge.register(rows, true)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] hour-of-day gauge refresh failed: {}", e.toString())
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
