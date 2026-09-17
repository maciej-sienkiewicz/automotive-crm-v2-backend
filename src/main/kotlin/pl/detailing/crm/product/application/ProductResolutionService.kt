package pl.detailing.crm.product.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.product.adapter.local.LocalCatalogProvider
import pl.detailing.crm.product.adapter.web.WebProductProvider
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.Provenance
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.port.ProductLookupResult
import pl.detailing.crm.shared.ValidationException
import java.time.Duration

/**
 * Rozpoznanie produktu po kodzie kreskowym. Dwa kroki i nic więcej:
 *
 *   1. NASZ KATALOG (globalny, współdzielony między studiami) — darmowy i natychmiastowy.
 *   2. WYSZUKIWANIE W SIECI — model z `web_search_options`, który przed odpowiedzią
 *      naprawdę szuka.
 *
 * Czego tu świadomie NIE MA i dlaczego:
 *  - modelu pytanego „z pamięci" — kod kreskowy to numer nadany przez GS1, nazwa produktu
 *    nie jest z niego wyprowadzalna, więc dla realnego kodu taki model zawsze oddawał
 *    `confidence: 0.0` i puste pola. Płacenie za to wywołanie nic nie wnosiło;
 *  - rejestru GS1 — „Verified by GS1" wymaga umowy licencyjnej, której nie ma, a atrapa
 *    zwracająca null tylko udawała krok łańcucha;
 *  - otwartych baz kodów i własnych zapytań do wyszukiwarki — dublowały to, co model
 *    wyszukujący robi jednym wywołaniem, i wymagały osobnych kluczy.
 *
 * Wynik z sieci niesie PEWNOŚĆ. Powyżej progu (domyślnie 0,90) jest trafieniem; poniżej —
 * wraca jako SZKIC do ręcznego potwierdzenia zamiast NOT_FOUND. Szkic to NIE jest wpis do
 * katalogu: nic nie zapisuje się samo i nic nie awansuje na „zweryfikowane" bez człowieka.
 *
 * Kody, dla których nie mamy nic (nawet szkicu), lądują w negatywnym cache w Redisie, żeby
 * każdy kolejny skan tego samego śmiecia nie płacił za wyszukiwanie. Klucz niesie
 * SYGNATURĘ ŹRÓDŁA (nazwę modelu): „miss" jest wnioskiem konkretnej konfiguracji, nie
 * faktem o kodzie — po zmianie modelu kod jest pytany od nowa. Bez tego każda poprawka
 * rozpoznawania była niewidoczna przez cały TTL dla wszystkich już zeskanowanych kodów.
 */
@Service
class ProductResolutionService(
    private val local: LocalCatalogProvider,
    private val web: WebProductProvider,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${crm.products.lookup.min-confidence:0.90}") private val minConfidence: Double,
    @Value("\${crm.products.lookup.draft-min-confidence:0.0}") private val draftMinConfidence: Double = 0.0,
    @Value("\${crm.products.lookup.negative-cache-ttl-days:7}") private val negativeCacheTtlDays: Long = 7
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws ValidationException gdy suma kontrolna kodu jest błędna (brama przed
     *   płatnym zapytaniem — kod z literówką nie kosztuje ani grosza).
     */
    suspend fun resolve(barcode: String): ProductResolution {
        val gtin = Gtin.parse(barcode)
        log.info(
            "[PRODUCT_LOOKUP] start barcode='{}' gtin={} ean={} min={} draftMin={} source={}",
            barcode, gtin.value, gtin.displayValue, minConfidence, draftMinConfidence, web.sourcesSignature
        )

        // Krok 1: nasz katalog. Trafienie omija negatywny cache — produkt mógł się w nim
        // pojawić już po tym, jak sieć go nie znała.
        localHit(gtin)?.let {
            log.info("[PRODUCT_LOOKUP] local_hit gtin={}", gtin.value)
            return ProductResolution.found(it, fromLocalCatalog = true)
        }

        if (isNegativelyCached(gtin)) {
            log.info("[PRODUCT_LOOKUP] negative_cache_hit gtin={} key={} — pomijam wyszukiwanie", gtin.value, negativeKey(gtin))
            return ProductResolution.notFound(gtin)
        }

        // Krok 2: wyszukiwanie w sieci.
        val found = webHit(gtin)
        if (found == null) {
            log.info("[PRODUCT_LOOKUP] not_found gtin={} — sieć nie zna tego kodu; zapisuję negatywny cache", gtin.value)
            markNegative(gtin)
            return ProductResolution.notFound(gtin)
        }

        return when {
            found.confidence >= minConfidence -> {
                log.info("[PRODUCT_LOOKUP] web_hit gtin={} confidence={}", gtin.value, found.confidence)
                ProductResolution.resolved(found)
            }
            found.confidence >= draftMinConfidence -> {
                // Łagodne zejście: zamiast wyrzucić najlepszy odczyt, pokazujemy go
                // człowiekowi z jawnie niską pewnością i banerem „sprawdź z etykietą".
                log.info("[PRODUCT_LOOKUP] web_draft gtin={} confidence={} < {}", gtin.value, found.confidence, minConfidence)
                ProductResolution.resolved(found)
            }
            else -> {
                log.info("[PRODUCT_LOOKUP] below_draft_floor gtin={} confidence={} < {}", gtin.value, found.confidence, draftMinConfidence)
                markNegative(gtin)
                ProductResolution.notFound(gtin)
            }
        }
    }

    // Awaria jednego kroku nie wywraca rozpoznania — idziemy dalej z pustym wynikiem.
    private suspend fun localHit(gtin: Gtin): ProductLookupResult? = try {
        local.findByGtin(gtin)
    } catch (e: Exception) {
        log.warn("[PRODUCT_LOOKUP] Katalog lokalny zawiódł dla {}: {}", gtin.value, e.message)
        null
    }

    private suspend fun webHit(gtin: Gtin): ProductLookupResult? = try {
        web.takeIf { it.enabled }?.findByGtin(gtin)
    } catch (e: Exception) {
        log.warn("[PRODUCT_LOOKUP] Wyszukiwanie w sieci zawiodło dla {}: {}", gtin.value, e.message)
        null
    }

    private fun negativeKey(gtin: Gtin) = "product:lookup:miss:${web.sourcesSignature}:${gtin.value}"

    private fun isNegativelyCached(gtin: Gtin): Boolean = try {
        redisTemplate.hasKey(negativeKey(gtin))
    } catch (e: Exception) {
        false
    }

    private fun markNegative(gtin: Gtin) {
        try {
            redisTemplate.opsForValue().set(negativeKey(gtin), "1", Duration.ofDays(negativeCacheTtlDays))
        } catch (e: Exception) {
            log.debug("[PRODUCT_LOOKUP] Nie udało się zapisać negatywnego cache: {}", e.message)
        }
    }

    /** Kod z katalogu przestał być „miss": czyścimy negatywny wpis po ręcznym dodaniu. */
    fun clearNegative(gtin: Gtin) {
        try {
            redisTemplate.delete(negativeKey(gtin))
        } catch (e: Exception) {
            log.debug("[PRODUCT_LOOKUP] Nie udało się wyczyścić negatywnego cache: {}", e.message)
        }
    }
}

/** Wynik: znaleziony w katalogu, rozpoznany w sieci, albo nieznany. */
data class ProductResolution(
    val status: Status,
    val result: ProductLookupResult?,
    val gtin: Gtin
) {
    enum class Status { FOUND_LOCAL, RESOLVED, NOT_FOUND }

    /**
     * Poziom weryfikacji wynikający ze źródła — nic nie awansuje samo (§3.2 architektury).
     *
     * `AI` i `GS1` zostają w mapowaniu jako wartości HISTORYCZNE: wiersze zapisane, zanim
     * te kroki zniknęły, dalej siedzą w bazie i muszą się odczytać.
     */
    fun verificationLevel(): VerificationLevel = when (result?.source) {
        ProductSource.GS1 -> VerificationLevel.GS1_VERIFIED
        // Dane ze sklepów to nie rejestr GS1 — człowiek potwierdza zgodność z etykietą.
        ProductSource.WEB, ProductSource.AI -> VerificationLevel.AI_SUGGESTED
        ProductSource.CURATED -> VerificationLevel.CURATED
        ProductSource.MANUAL, null -> VerificationLevel.UNVERIFIED
    }

    fun provenance(): Provenance? = result?.let {
        Provenance(
            source = it.source,
            verificationLevel = verificationLevel(),
            confidence = if (it.source == ProductSource.WEB || it.source == ProductSource.AI) it.confidence else null
        )
    }

    companion object {
        fun found(r: ProductLookupResult, fromLocalCatalog: Boolean) =
            ProductResolution(if (fromLocalCatalog) Status.FOUND_LOCAL else Status.RESOLVED, r, Gtin.parse(r.spec.gtin))
        fun resolved(r: ProductLookupResult) = ProductResolution(Status.RESOLVED, r, Gtin.parse(r.spec.gtin))
        fun notFound(gtin: Gtin) = ProductResolution(Status.NOT_FOUND, null, gtin)
    }
}
