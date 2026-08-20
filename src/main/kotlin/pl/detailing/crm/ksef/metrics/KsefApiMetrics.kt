package pl.detailing.crm.ksef.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.config.KsefProperties
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.observability.MetricsTags
import java.util.concurrent.ConcurrentHashMap

/**
 * Ruch do API KSeF w rozbiciu na najemców.
 *
 * KSeF liczy limity per kontekst NIP, więc pytanie „ile żądań wykonujemy" ma sens
 * wyłącznie zadane osobno dla każdego studia — suma platformy nie mówi nic o tym,
 * kto zaraz zobaczy 429. Metryki odpowiadają na dwa pytania:
 *
 *  - ile żądań i jakich wykonaliśmy dla studia (licznik, historia w Prometheusie),
 *  - jak blisko limitu jest studio **teraz** (wskaźniki okna minutowego i godzinowego).
 *
 * Kardynalność jest ograniczona z założenia: etykieta `operation` to skończony zbiór
 * metod klienta KSeF (kilkanaście), `outcome` ma trzy wartości, a wskaźniki okien mają
 * po dwie serie na studio. To rząd wielkości, który Prometheus uniesie — inaczej niż
 * odrzucony w tym module pomysł etykietowania po `studio_id` wszystkich endpointów CRM.
 *
 * Progi to najostrzejsze udokumentowane limity KSeF (16/min, 64/h dla pobrania faktury).
 * Odnoszenie do nich całego ruchu studia jest świadomie ostrożne: wskaźnik zapala się
 * wcześniej, niż KSeF faktycznie odmówi. Wolimy ostrzeżenie o minutę za wcześnie
 * niż raport o blokadzie, która już trwa.
 */
@Component
class KsefApiMetrics(
    private val registry: MeterRegistry,
    private val ksefProperties: KsefProperties,
    private val metricsProperties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(KsefApiMetrics::class.java)

    companion object {
        /** Żądanie zakończone odpowiedzią KSeF. */
        const val OUTCOME_SUCCESS = "success"

        /** KSeF odmówił z powodu limitu (HTTP 429). */
        const val OUTCOME_RATE_LIMITED = "rate_limited"

        /** Każdy inny błąd: sieć, walidacja, 5xx. */
        const val OUTCOME_ERROR = "error"

        /** Powód wstrzymania przebiegu: wyczerpany budżet pobrań XML po naszej stronie. */
        const val DEFERRED_XML_BUDGET = "xml_budget"

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L

        /** Sufit rozmiaru okna na studio — zabezpieczenie przed nieograniczonym wzrostem. */
        private const val MAX_TRACKED_TIMESTAMPS = 5_000
    }

    private val windows = ConcurrentHashMap<String, RequestWindow>()

    private lateinit var windowGauge: MultiGauge
    private lateinit var utilizationGauge: MultiGauge

    @PostConstruct
    fun register() {
        if (!metricsProperties.enabled) return

        windowGauge = MultiGauge.builder(MetricsTags.KSEF_API_WINDOW_REQUESTS)
            .description("Liczba żądań do API KSeF w oknie czasowym, per studio")
            .register(registry)

        utilizationGauge = MultiGauge.builder(MetricsTags.KSEF_API_WINDOW_UTILIZATION)
            .description("Wykorzystanie limitu żądań KSeF w oknie czasowym (0–1), per studio")
            .register(registry)
    }

    /**
     * Rejestruje jedno żądanie do KSeF.
     *
     * @param studioTag identyfikator studia albo [KsefTenantContext.SYSTEM]
     * @param operation nazwa operacji klienta KSeF (snake_case)
     * @param outcome   [OUTCOME_SUCCESS] / [OUTCOME_RATE_LIMITED] / [OUTCOME_ERROR]
     */
    fun record(studioTag: String, operation: String, outcome: String) {
        if (!metricsProperties.enabled) return

        registry.counter(
            MetricsTags.KSEF_API_REQUESTS,
            MetricsTags.TAG_STUDIO_ID, studioTag,
            MetricsTags.TAG_KSEF_OPERATION, operation,
            MetricsTags.TAG_RESULT, outcome
        ).increment()

        windows.computeIfAbsent(studioTag) { RequestWindow() }.add(System.currentTimeMillis())
    }

    /**
     * Rejestruje wstrzymanie własnego przebiegu, gdy budżet żądań się wyczerpał.
     *
     * To inny sygnał niż 429: nie znaczy „KSeF odmówił", tylko „my przestaliśmy pytać,
     * żeby nie odmówił". Rosnąca wartość mówi, że studio ma więcej dokumentów do
     * pobrania, niż mieści się w limicie — czyli że synchronizacja się nie domyka.
     */
    fun recordDeferred(studioTag: String, reason: String) {
        if (!metricsProperties.enabled) return

        registry.counter(
            MetricsTags.KSEF_API_DEFERRED,
            MetricsTags.TAG_STUDIO_ID, studioTag,
            MetricsTags.TAG_REASON, reason
        ).increment()
    }

    /**
     * Odświeżane z harmonogramu, a nie przy scrapie: [MultiGauge.register] przebudowuje
     * rejestracje mierników, czego nie robi się w callbacku scrape'a. Co 30 s jest
     * gęściej niż typowy scrape, więc alert na zbliżanie się do limitu ma świeże dane.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    fun refreshGauges() {
        if (!metricsProperties.enabled) return

        val now = System.currentTimeMillis()
        val minuteLimit = ksefProperties.requestsPerMinuteLimit.toDouble()
        val hourLimit = ksefProperties.requestsPerHourLimit.toDouble()

        // Studia, które przez godzinę nic nie wysłały, znikają z map i z serii —
        // wskaźnik zatrzymany na ostatniej wartości kłamałby o bieżącym obciążeniu
        windows.entries.removeIf { (_, window) -> window.prune(now) == 0 }

        val counts = windows.map { (studioTag, window) -> studioTag to window.counts(now) }

        windowGauge.register(
            counts.flatMap { (studioTag, c) ->
                listOf(
                    MultiGauge.Row.of(rowTags(studioTag, "minute"), c.lastMinute.toDouble()),
                    MultiGauge.Row.of(rowTags(studioTag, "hour"), c.lastHour.toDouble())
                )
            },
            true
        )

        utilizationGauge.register(
            counts.flatMap { (studioTag, c) ->
                listOf(
                    MultiGauge.Row.of(rowTags(studioTag, "minute"), ratio(c.lastMinute, minuteLimit)),
                    MultiGauge.Row.of(rowTags(studioTag, "hour"), ratio(c.lastHour, hourLimit))
                )
            },
            true
        )
    }

    private fun rowTags(studioTag: String, window: String): Tags =
        Tags.of(MetricsTags.TAG_STUDIO_ID, studioTag, MetricsTags.TAG_KSEF_WINDOW, window)

    private fun ratio(count: Int, limit: Double): Double =
        if (limit <= 0) 0.0 else count / limit

    private data class WindowCounts(val lastMinute: Int, val lastHour: Int)

    /**
     * Znaczniki czasu żądań jednego studia z ostatniej godziny. Kolejka, nie licznik:
     * limity KSeF działają w oknie przesuwanym, więc „ile w ostatniej godzinie" trzeba
     * umieć policzyć w dowolnej chwili, a nie tylko na granicy pełnej godziny.
     */
    private inner class RequestWindow {
        private val timestamps = ArrayDeque<Long>()

        @Synchronized
        fun add(nowMs: Long) {
            prune(nowMs)
            if (timestamps.size >= MAX_TRACKED_TIMESTAMPS) {
                // Nie do osiągnięcia przy limitach KSeF; gdyby jednak — logujemy i przycinamy,
                // bo metryka nigdy nie może być powodem wycieku pamięci
                log.warn("Okno żądań KSeF przekroczyło {} wpisów — przycinam", MAX_TRACKED_TIMESTAMPS)
                timestamps.removeFirst()
            }
            timestamps.addLast(nowMs)
        }

        /** Usuwa wpisy starsze niż godzina i zwraca rozmiar okna. */
        @Synchronized
        fun prune(nowMs: Long): Int {
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() >= HOUR_MS) {
                timestamps.removeFirst()
            }
            return timestamps.size
        }

        @Synchronized
        fun counts(nowMs: Long): WindowCounts {
            prune(nowMs)
            return WindowCounts(
                lastMinute = timestamps.count { nowMs - it < MINUTE_MS },
                lastHour = timestamps.size
            )
        }
    }
}
