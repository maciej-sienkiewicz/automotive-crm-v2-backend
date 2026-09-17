package pl.detailing.crm.product.application

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.product.adapter.ai.AiProductProvider
import pl.detailing.crm.product.adapter.gs1.Gs1ProductProvider
import pl.detailing.crm.product.adapter.local.LocalCatalogProvider
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.Provenance
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.port.ProductLookupResult
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Duration

/**
 * Łańcuch rozpoznawania produktu po kodzie: LOKALNY → AI (z weryfikatorem) → GS1.
 *
 * Kolejność i próg pewności są KONFIGURACJĄ, nie kodem (`crm.products.resolution.*`),
 * więc zmiana na LOCAL,GS1,AI to jedna właściwość, bez deployu. Wynik AI powyżej progu
 * (domyślnie 0,90) jest trafieniem pewnym; poniżej progu łańcuch najpierw próbuje kolejnych
 * dostawców (np. GS1), a gdy żaden nie da pewnego trafienia, oddaje najlepszą kartę AI jako
 * SZKIC do ręcznego potwierdzenia — zamiast NOT_FOUND.
 *
 * To „łagodniejsze" zachowanie jest świadome: GTIN to numer, którego model nie potrafi
 * pewnie zmapować na produkt, więc zamiast wyrzucać jego najlepszy odczyt, pokazujemy go
 * człowiekowi z jawnie niską pewnością (`AI_SUGGESTED`) i banerem „sprawdź z etykietą".
 * Nic nie zapisuje się samo i nic nie awansuje na „zweryfikowane" bez człowieka —
 * niezmiennik „nie zmyślamy produktu do katalogu" pozostaje: szkic to nie wpis.
 *
 * Trafienie lokalne jest darmowym cache'em (sam katalog globalny). Kody, dla których NIC
 * nie mamy (nawet szkicu), lądują w negatywnym cache w Redisie, żeby każdy kolejny skan
 * tego samego śmiecia nie płacił za odpytanie zewnętrzne. Kodu, który dał szkic, NIE
 * cache'ujemy jako „miss" — kolejny skan ma znów pokazać kartę do potwierdzenia.
 */
@Service
class ProductResolutionService(
    private val local: LocalCatalogProvider,
    private val ai: AiProductProvider,
    private val gs1: Gs1ProductProvider,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${crm.products.resolution.order:LOCAL,AI,GS1}") private val orderRaw: String,
    @Value("\${crm.products.resolution.ai-min-confidence:0.90}") private val aiMinConfidence: Double,
    @Value("\${crm.products.resolution.ai-draft-min-confidence:0.0}") private val aiDraftMinConfidence: Double = 0.0,
    @Value("\${crm.products.lookup.negative-cache-ttl-days:7}") private val negativeCacheTtlDays: Long = 7,
    // Część klucza negatywnego cache — patrz [negativeKey].
    @Value("\${crm.ai.product-lookup.model:gpt-4.1}") private val lookupModel: String = "gpt-4.1"
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val order: List<String> by lazy {
        orderRaw.split(",").map { it.trim().uppercase() }.filter { it.isNotBlank() }
    }

    /**
     * @throws ValidationException gdy suma kontrolna kodu jest błędna (brama przed
     *   płatnym zapytaniem — kod z literówką nie kosztuje ani grosza).
     */
    suspend fun resolve(barcode: String): ProductResolution {
        val gtin = Gtin.parse(barcode)
        log.info(
            "[PRODUCT_LOOKUP] start barcode='{}' gtin={} order={} aiMin={} draftMin={} model={}",
            barcode, gtin.value, order, aiMinConfidence, aiDraftMinConfidence, lookupModel
        )

        // Krok 0.5: negatywny cache — kod, którego nikt nie zna, nie jest odpytywany
        // ponownie przy każdym skanie. Trafienie lokalne go omija (może się pojawił).
        val localHit = runProvider("LOCAL", gtin)
        if (localHit != null) {
            log.info("[PRODUCT_LOOKUP] local_hit gtin={}", gtin.value)
            return ProductResolution.found(localHit, fromLocalCatalog = true)
        }
        if (isNegativelyCached(gtin)) {
            // INFO, nie DEBUG: to jest najczęstsza przyczyna „nadal NOT_FOUND" po zmianie
            // modelu/progów — bez tej linii w logach produkcyjnych nie widać, że LLM w ogóle
            // nie został zapytany.
            log.info("[PRODUCT_LOOKUP] negative_cache_hit gtin={} key={} — pomijam dostawców zewnętrznych", gtin.value, negativeKey(gtin))
            return ProductResolution.notFound(gtin)
        }

        // Najlepszy kandydat AI poniżej progu — trzymamy go na wypadek, gdyby żaden pewny
        // dostawca nie odpowiedział; wtedy oddamy go jako szkic zamiast NOT_FOUND.
        var bestDraft: ProductLookupResult? = null

        for (name in order) {
            if (name == "LOCAL") continue // już sprawdzony wyżej
            val result = runProvider(name, gtin) ?: continue

            when (name) {
                "AI" -> if (result.confidence >= aiMinConfidence) {
                    log.info("[PRODUCT_LOOKUP] ai_hit gtin={} confidence={}", gtin, result.confidence)
                    return ProductResolution.resolved(result)
                } else {
                    log.info(
                        "[PRODUCT_LOOKUP] ai_below_threshold gtin={} confidence={} < {} — próbuję dalej, zachowuję jako szkic",
                        gtin, result.confidence, aiMinConfidence
                    )
                    if (result.confidence >= aiDraftMinConfidence &&
                        (bestDraft == null || result.confidence > bestDraft!!.confidence)
                    ) {
                        bestDraft = result
                    }
                }
                else -> {
                    log.info("[PRODUCT_LOOKUP] {}_hit gtin={}", name.lowercase(), gtin)
                    return ProductResolution.resolved(result)
                }
            }
        }

        // Żaden dostawca nie dał pewnego trafienia. Jeśli AI ma sensowny odczyt, oddajemy
        // go jako SZKIC do potwierdzenia (łagodne zejście) — inaczej dopiero NOT_FOUND.
        bestDraft?.let {
            log.info("[PRODUCT_LOOKUP] ai_draft_returned gtin={} confidence={}", gtin, it.confidence)
            return ProductResolution.resolved(it)
        }

        log.info("[PRODUCT_LOOKUP] not_found gtin={} — żaden dostawca nie dał karty ani szkicu; zapisuję negatywny cache", gtin.value)
        markNegative(gtin)
        return ProductResolution.notFound(gtin)
    }

    private suspend fun runProvider(name: String, gtin: Gtin): ProductLookupResult? = try {
        when (name) {
            "LOCAL" -> local.findByGtin(gtin)
            "AI" -> ai.takeIf { it.enabled }?.findByGtin(gtin)
            "GS1" -> gs1.takeIf { it.enabled }?.findByGtin(gtin)
            else -> {
                log.warn("[PRODUCT_LOOKUP] Nieznany dostawca w konfiguracji kolejności: {}", name)
                null
            }
        }
    } catch (e: Exception) {
        // Awaria jednego dostawcy nie wywraca łańcucha — idziemy dalej.
        log.warn("[PRODUCT_LOOKUP] Dostawca {} zawiódł dla {}: {}", name, gtin, e.message)
        null
    }

    /**
     * Klucz zawiera NAZWĘ MODELU odczytu. „Miss" jest wnioskiem konkretnego modelu, nie
     * faktem o kodzie: po przejściu na mocniejszy model stare wpisy przestają pasować i
     * kod jest pytany od nowa. Bez tego zmiana modelu nie miałaby żadnego efektu przez
     * cały TTL (7 dni) dla wszystkich już zeskanowanych kodów — dokładnie taki objaw
     * zgłoszono z produkcji.
     */
    private fun negativeKey(gtin: Gtin) = "product:lookup:miss:$lookupModel:${gtin.value}"

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

/** Wynik łańcucha: znaleziony w katalogu, rozpoznany zewnętrznie, albo nieznany. */
data class ProductResolution(
    val status: Status,
    val result: ProductLookupResult?,
    val gtin: Gtin
) {
    enum class Status { FOUND_LOCAL, RESOLVED, NOT_FOUND }

    /** Poziom weryfikacji wynikający ze źródła — nic nie awansuje samo (§3.2 architektury). */
    fun verificationLevel(): VerificationLevel = when (result?.source) {
        ProductSource.GS1 -> VerificationLevel.GS1_VERIFIED
        ProductSource.AI -> VerificationLevel.AI_SUGGESTED
        ProductSource.CURATED -> VerificationLevel.CURATED
        ProductSource.MANUAL, null -> VerificationLevel.UNVERIFIED
    }

    fun provenance(): Provenance? = result?.let {
        Provenance(
            source = it.source,
            verificationLevel = verificationLevel(),
            confidence = if (it.source == ProductSource.AI) it.confidence else null
        )
    }

    companion object {
        fun found(r: ProductLookupResult, fromLocalCatalog: Boolean) =
            ProductResolution(if (fromLocalCatalog) Status.FOUND_LOCAL else Status.RESOLVED, r, Gtin.parse(r.spec.gtin))
        fun resolved(r: ProductLookupResult) = ProductResolution(Status.RESOLVED, r, Gtin.parse(r.spec.gtin))
        fun notFound(gtin: Gtin) = ProductResolution(Status.NOT_FOUND, null, gtin)
    }
}
