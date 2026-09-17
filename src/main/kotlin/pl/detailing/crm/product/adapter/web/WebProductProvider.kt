package pl.detailing.crm.product.adapter.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.PackageDimensions
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.ProductSpec
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.port.ProductDataProvider
import pl.detailing.crm.product.port.ProductLookupResult
import java.math.BigDecimal

/**
 * Rozpoznanie kodu na podstawie DANYCH Z SIECI.
 *
 * Powód istnienia tego dostawcy: model językowy nie ma dostępu do internetu i nie
 * pamięta tablicy EAN → produkt. Zapytany o „05902806493015" uczciwie odpowiada
 * confidence 0.0, choć ten sam kod wklejony w wyszukiwarkę zwraca dziesiątki ofert.
 * Brakującym ogniwem nie był więc mocniejszy model, tylko DOSTĘP DO DANYCH.
 *
 * Dwa kroki, od najtańszego:
 *  1. [OpenFactsClient] — otwarte bazy kodów, za darmo i bez klucza, dane gotowe.
 *  2. [BarcodeWebSearchClient] + [WebProductExtractionService] — wyniki wyszukiwarki
 *     jako kontekst, z którego model WYDOBYWA kartę (wymaga klucza, domyślnie wyłączone).
 *
 * Kod wychodzi w postaci drukowanej (EAN-13, bez wiodących zer) — patrz [Gtin.displayValue].
 */
@Component
class WebProductProvider(
    private val openFacts: OpenFactsClient,
    private val search: BarcodeWebSearchClient,
    private val extraction: WebProductExtractionService
) : ProductDataProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val source = ProductSource.WEB

    override val enabled: Boolean
        get() = openFacts.enabled || search.enabled

    /** Do sygnatury negatywnego cache: zmiana źródeł musi unieważnić stare „miss". */
    val sourcesSignature: String
        get() = "of=${openFacts.enabled}|s=${search.providerName}"

    override suspend fun findByGtin(gtin: Gtin): ProductLookupResult? = withContext(Dispatchers.IO) {
        val ean = gtin.displayValue
        log.info("[PRODUCT_WEB] start gtin={} ean={} openFacts={} search={}", gtin.value, ean, openFacts.enabled, search.providerName)

        val card = openFacts.lookup(ean)
            ?: extraction.extract(ean, search.search(ean))
            ?: run {
                log.info("[PRODUCT_WEB] no_data ean={}", ean)
                return@withContext null
            }

        if (card.name.isNullOrBlank() || card.brand.isNullOrBlank()) return@withContext null

        val sizeUnit = UnitOfMeasure.fromCode(card.packageSizeUnit) ?: UnitOfMeasure.PIECE
        val spec = ProductSpec(
            gtin = gtin.value,
            name = card.name.trim(),
            brand = card.brand.trim(),
            // Chemia jest sprzedawana w sztukach (butelka/kanister); pojemność opisuje
            // ZAWARTOŚĆ tej sztuki i siedzi w packageSize*.
            unitOfMeasure = UnitOfMeasure.PIECE,
            packageSizeValue = card.packageSizeValue
                ?.let { runCatching { BigDecimal(it.replace(',', '.')) }.getOrNull() }
                ?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE,
            packageSizeUnit = sizeUnit,
            dimensions = PackageDimensions.EMPTY,
            description = card.description,
            imageFileId = null
        )
        log.info("[PRODUCT_WEB] resolved ean={} confidence={} brand='{}' name='{}' src={}", ean, card.confidence, card.brand, card.name, card.sourceUrl)
        ProductLookupResult(
            spec = spec,
            source = ProductSource.WEB,
            confidence = card.confidence,
            rawPayload = card.sourceUrl
        )
    }
}
