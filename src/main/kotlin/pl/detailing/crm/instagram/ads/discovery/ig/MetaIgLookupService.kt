package pl.detailing.crm.instagram.ads.discovery.ig

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ustalanie i przechowywanie nazw profili IG reklamodawców.
 *
 * Odczyt tabeli reklamodawców NIGDY nie wywołuje tu niczego, co sięga do sieci —
 * czyta wyłącznie to, co już zapisane. Uzupełnianiem zajmuje się
 * [MetaIgBackfillScheduler] w tle, po kilka stron na raz. Powód jest prosty:
 * uruchomienie przeglądarki trwa kilkanaście sekund, a ekran ma się otworzyć
 * natychmiast. Nazwa profilu to ozdoba wiersza, nie jego treść.
 */
@Service
class MetaIgLookupService(
    private val repository: MetaPageIgLookupRepository,
    private val resolver: MetaIgResolverClient,
    private val registry: MeterRegistry,
    @Value("\${meta.ads.ig-resolver.retry-days:30}") retryDays: Long
) {
    private val log = LoggerFactory.getLogger(MetaIgLookupService::class.java)

    /**
     * Po ilu dniach wracamy do strony, przy której nic nie ustaliliśmy.
     *
     * Dotyczy TAKŻE wyniku [IgLookupStatus.EMPTY]: reklamodawca mógł w tym czasie
     * podpiąć Instagram do swojej strony. Nie dotyczy wierszy z nazwą — ta się
     * nie zmienia, a ponawianie byłoby ruchem do Meta bez żadnego powodu.
     */
    private val retryAfter: Duration = Duration.ofDays(retryDays)

    /**
     * Liczniki prób, po jednym na wynik — zarejestrowane z góry, wszystkie.
     *
     * Rejestrujemy je od razu przy starcie, a nie leniwie przy pierwszym
     * wystąpieniu, bo alert w Prometheuszu musi mieć szereg, który istnieje
     * od początku. Szereg pojawiający się dopiero w chwili pierwszej awarii
     * jest bezużyteczny dla reguł liczących udział błędów.
     */
    private val counters: Map<IgLookupStatus, Counter> = IgLookupStatus.entries.associateWith { status ->
        Counter.builder("crm.meta_ig.lookups")
            .description("Próby odczytania nazwy profilu IG reklamodawcy")
            .tag("result", status.name.lowercase())
            .register(registry)
    }

    /**
     * Stan kanarka: 1 = mechanizm działa, 0 = przestał, -1 = jeszcze nie wiadomo.
     *
     * To JEST mechanizm wykrywania przebudowy strony przez Meta. Same liczniki
     * by nie wystarczyły: gdy ścieżka kliknięć przestanie pasować, wyniki nie
     * zaczną wyglądać na błędne — zaczną wyglądać na „ten reklamodawca nie ma
     * Instagrama". Cisza nie do odróżnienia od prawdy. Dlatego raz na dobę
     * pytamy o stronę, o której WIEMY, że profil ma. Szczegóły w [MetaIgCanary].
     */
    private val canaryState = AtomicInteger(-1)

    init {
        Gauge.builder("crm.meta_ig.canary") { canaryState.get().toDouble() }
            .description("1 = odczyt profilu IG działa, 0 = przestał, -1 = jeszcze nie sprawdzono")
            .register(registry)
    }

    /** Nazwy profili dla podanych stron — WYŁĄCZNIE z tego, co już zapisane. */
    @Transactional(readOnly = true)
    fun known(pageIds: Collection<String>): Map<String, String> {
        if (pageIds.isEmpty()) return emptyMap()
        return repository.findAllById(pageIds.distinct())
            .mapNotNull { row -> row.igUsername?.let { row.pageId to it } }
            .toMap()
    }

    /** Strony, o które jeszcze nie pytaliśmy albo pytaliśmy dawno i bez skutku. */
    @Transactional(readOnly = true)
    fun needingLookup(candidates: Collection<String>, limit: Int): List<String> {
        if (candidates.isEmpty() || limit <= 0) return emptyList()
        val unique = candidates.distinct()
        val known = repository.findAllById(unique).associateBy { it.pageId }
        val threshold = Instant.now().minus(retryAfter)

        return unique.asSequence()
            .filter { pageId ->
                val row = known[pageId]
                row == null || (row.igUsername == null && row.checkedAt.isBefore(threshold))
            }
            .take(limit)
            .toList()
    }

    /**
     * Pyta sidecar i zapisuje wynik. Własna transakcja, bo wołane w pętli —
     * niepowodzenie przy jednej stronie nie ma cofać zapisów poprzednich.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun lookupAndStore(pageId: String): IgLookupResult {
        val result = resolver.resolve(pageId)
        counters.getValue(result.status).increment()

        val row = repository.findById(pageId).orElse(null)
        if (row == null) {
            repository.save(
                MetaPageIgLookupEntity(
                    pageId = pageId,
                    igUsername = result.igUsername,
                    status = result.status,
                    checkedAt = Instant.now(),
                    attempts = 1
                )
            )
        } else {
            // Nazwy raz ustalonej nie kasujemy nieudaną próbą. Gdyby sidecar
            // zaczął zwracać puste wyniki po przebudowie strony, dane już
            // zebrane mają to przetrwać.
            row.igUsername = result.igUsername ?: row.igUsername
            row.status = result.status
            row.checkedAt = Instant.now()
            row.attempts += 1
            repository.save(row)
        }

        if (result.status.isFailure) {
            log.info("Profil IG strony {}: {} ({})", pageId, result.status, result.reason)
        }
        return result
    }

    internal fun markCanary(ok: Boolean) = canaryState.set(if (ok) 1 else 0)
}
