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
 * Jedyne zewnętrzne źródło danych o produkcie: wyszukiwanie w sieci ([OpenAiWebSearchClient]).
 *
 * Kod wychodzi na zewnątrz w postaci DRUKOWANEJ (EAN-13, bez wiodących zer) — patrz
 * [Gtin.displayValue]. Wewnątrz katalog kluczujemy GTIN-em-14, ale „05902806493015" nie
 * znajduje w sieci niczego, a „5902806493015" znajduje produkt.
 */
@Component
class WebProductProvider(
    private val search: OpenAiWebSearchClient
) : ProductDataProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val source = ProductSource.WEB

    override val enabled: Boolean
        get() = search.enabled

    /** Do sygnatury negatywnego cache: zmiana źródła musi unieważnić stare „miss". */
    val sourcesSignature: String
        get() = search.signature

    override suspend fun findByGtin(gtin: Gtin): ProductLookupResult? = withContext(Dispatchers.IO) {
        val ean = gtin.displayValue
        val card = search.lookup(ean) ?: return@withContext null
        if (card.name.isNullOrBlank() || card.brand.isNullOrBlank()) return@withContext null

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
            packageSizeUnit = UnitOfMeasure.fromCode(card.packageSizeUnit) ?: UnitOfMeasure.PIECE,
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
