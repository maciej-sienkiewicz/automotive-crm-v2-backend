package pl.detailing.crm.ksef.fetch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.detailing.crm.ksef.metrics.KsefApiMetrics
import pl.detailing.crm.ksef.metrics.KsefTenantContext
import pl.detailing.crm.shared.StudioId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wyczerpanie limitu żądań KSeF, którego nie opłaca się przeczekiwać w bieżącym
 * przebiegu synchronizacji. Wołający przerywa pobieranie XML — dokumenty bez
 * szczegółów mają `details_synced = FALSE` i zostaną dokończone w kolejnym cyklu.
 */
class KsefRateLimitException(message: String) : RuntimeException(message)

/**
 * Jedyne wejście do `GET /invoices/ksef/{numer}` — pobiera i parsuje XML faktury,
 * pilnując limitów KSeF API 2.0: **8 req/s, 16 req/min, 64 req/h**, naliczanych
 * per kontekst (NIP + adres IP) w przesuwającym się oknie czasowym.
 *
 * Budżet jest wspólny dla kosztów i przychodów, bo KSeF nie rozróżnia, po co pytamy —
 * to samo studio pobiera XML faktur kosztowych (SUBJECT2) i sprzedażowych (SUBJECT1)
 * z jednej puli. Osobne liczniki w każdym handlerze podwajałyby realne tempo żądań
 * i kończyły się serią 429 zamiast równomiernego zużycia limitu. Stan trzymamy per
 * studio: różne NIP-y to różne konteksty, więc dzielenie ich budżetu byłoby zbyt ostrożne.
 *
 * Limit godzinowy pilnujemy sami, z zapasem względem twardego 64 req/h — dojście
 * do 429 kosztuje blokadę liczoną w minutach, a przerwanie przebiegu kosztuje tyle,
 * że reszta dokumentów poczeka do następnego cyklu syncu.
 */
@Component
class KsefInvoiceXmlFetcher(
    private val ksefClient: KSeFClient,
    private val xmlParser: KsefInvoiceXmlParser,
    private val apiMetrics: KsefApiMetrics
) {
    private val log = LoggerFactory.getLogger(KsefInvoiceXmlFetcher::class.java)

    companion object {
        /** Minimalny odstęp między pobraniami: 4 s → maks. 15 req/min, poniżej limitu 16/min. */
        private const val FETCH_INTERVAL_MS = 4_000L

        /** Własny sufit godzinowy — zapas względem twardego limitu 64 req/h. */
        private const val MAX_REQUESTS_PER_HOUR = 56

        private const val HOUR_MS = 3_600_000L

        /** Maks. liczba ponowień po 429 dla jednego pobrania. */
        private const val MAX_RATE_LIMIT_RETRIES = 2

        /**
         * Gdy Retry-After przekracza ten próg, blokada dotyczy limitu minutowego lub
         * godzinowego — nie czekamy aktywnie, tylko przerywamy przebieg.
         */
        private const val MAX_RETRY_AFTER_SECONDS = 15L

        /** Domyślny czas oczekiwania po 429, gdy nie udało się odczytać Retry-After. */
        private const val DEFAULT_RETRY_AFTER_SECONDS = 2L

        /** Wyciąga sugerowany czas oczekiwania z komunikatu 429 („Spróbuj ponownie po N sekundach"). */
        private val RETRY_AFTER_PATTERN = Regex("po (\\d+) sekund")
    }

    private val budgets = ConcurrentHashMap<UUID, StudioBudget>()

    /**
     * Pobiera i parsuje XML faktury o podanym numerze KSeF.
     *
     * @return dane z XML albo null, gdy pobranie lub parsowanie nie powiodło się
     *         z przyczyn innych niż limit żądań (wołający zapisze sam nagłówek)
     * @throws KsefRateLimitException gdy budżet żądań jest wyczerpany — sygnał
     *         do przerwania całego przebiegu, nie do pominięcia jednego dokumentu
     */
    fun fetch(studioId: StudioId, ksefNumber: String, accessToken: String): KsefXmlData? =
        KsefTenantContext.withStudio(studioId) { doFetch(studioId, ksefNumber, accessToken) }

    private fun doFetch(studioId: StudioId, ksefNumber: String, accessToken: String): KsefXmlData? {
        val budget = budgets.computeIfAbsent(studioId.value) { StudioBudget() }
        var attempt = 0

        while (true) {
            try {
                budget.awaitSlot(ksefNumber)
            } catch (e: KsefRateLimitException) {
                // Odróżniamy „my przestaliśmy pytać" od „KSeF odmówił" — pierwsze
                // znaczy, że studio ma więcej dokumentów, niż mieści się w limicie,
                // i że synchronizacja nie domknie się w tym przebiegu
                apiMetrics.recordDeferred(
                    studioId.value.toString(),
                    KsefApiMetrics.DEFERRED_XML_BUDGET
                )
                throw e
            }
            try {
                val xml: ByteArray = ksefClient.getInvoice(ksefNumber, accessToken)
                return xmlParser.parseInvoiceData(xml)
            } catch (e: Exception) {
                if (!isRateLimited(e)) {
                    log.warn("Nie udało się pobrać XML faktury {}: {}", ksefNumber, e.message)
                    return null
                }

                val retryAfter = retryAfterSeconds(e)
                attempt++
                if (attempt > MAX_RATE_LIMIT_RETRIES || retryAfter > MAX_RETRY_AFTER_SECONDS) {
                    throw KsefRateLimitException(
                        "429 dla faktury $ksefNumber (retryAfter=${retryAfter}s, próba $attempt)"
                    )
                }
                log.info(
                    "KSeF 429 dla faktury {} — ponowienie za {}s (próba {}/{})",
                    ksefNumber, retryAfter, attempt, MAX_RATE_LIMIT_RETRIES
                )
                Thread.sleep(retryAfter * 1000)
            }
        }
    }

    /**
     * KSeF zwraca 429 z opisem w treści błędu; SDK opakowuje odpowiedź w wyjątek
     * z komunikatem zawierającym kod statusu, stąd detekcja po treści komunikatu.
     */
    private fun isRateLimited(e: Exception): Boolean = e.message?.contains("429") == true

    private fun retryAfterSeconds(e: Exception): Long =
        RETRY_AFTER_PATTERN.find(e.message ?: "")
            ?.groupValues?.get(1)?.toLongOrNull()
            ?: DEFAULT_RETRY_AFTER_SECONDS

    /** Budżet żądań jednego studia: odstęp między pobraniami i sufit w oknie godziny. */
    private inner class StudioBudget {
        private var lastFetchAtMs = 0L
        private val recentFetches = ArrayDeque<Long>()

        /**
         * Czeka na wolny slot i rezerwuje go. Synchronizowane, bo scheduler może
         * synchronizować kilka studiów równolegle, a dwa wątki jednego studia
         * odczekałyby ten sam odstęp i wyszły z niego razem.
         */
        @Synchronized
        fun awaitSlot(ksefNumber: String) {
            val now = System.currentTimeMillis()
            while (recentFetches.isNotEmpty() && now - recentFetches.first() >= HOUR_MS) {
                recentFetches.removeFirst()
            }
            if (recentFetches.size >= MAX_REQUESTS_PER_HOUR) {
                val freeInSeconds = (HOUR_MS - (now - recentFetches.first())) / 1000
                throw KsefRateLimitException(
                    "Wyczerpany godzinowy budżet pobrań XML ($MAX_REQUESTS_PER_HOUR/h) " +
                        "— faktura $ksefNumber poczeka ~${freeInSeconds}s"
                )
            }

            val waitMs = lastFetchAtMs + FETCH_INTERVAL_MS - now
            if (waitMs > 0) Thread.sleep(waitMs)

            lastFetchAtMs = System.currentTimeMillis()
            recentFetches.addLast(lastFetchAtMs)
        }
    }
}
