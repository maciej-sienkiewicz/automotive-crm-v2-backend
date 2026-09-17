package pl.detailing.crm.product.adapter.local

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.infrastructure.ProductRepository
import pl.detailing.crm.product.port.ProductDataProvider
import pl.detailing.crm.product.port.ProductLookupResult

/**
 * Krok 1 łańcucha: sam katalog globalny jest cache'em. Drugie studio skanujące ten
 * sam kod nie wywołuje NICZEGO zewnętrznego — to jest cały zwrot z inwestycji we
 * wspólną tabelę bez `studio_id`.
 */
@Component
class LocalCatalogProvider(
    private val productRepository: ProductRepository
) : ProductDataProvider {

    override val source = ProductSource.MANUAL   // nieużywane przy trafieniu lokalnym

    @Transactional(readOnly = true)
    override suspend fun findByGtin(gtin: Gtin): ProductLookupResult? {
        val entity = productRepository.findByGtin(gtin.value)?.takeIf { !it.isWithdrawn } ?: return null
        return ProductLookupResult(
            spec = entity.toSpec(),
            source = entity.source,
            confidence = entity.sourceConfidence?.toDouble() ?: 1.0,
            rawPayload = null
        )
    }
}
