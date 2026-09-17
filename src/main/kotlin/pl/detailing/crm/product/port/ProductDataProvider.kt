package pl.detailing.crm.product.port

import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.ProductSpec

/**
 * Jeden dostawca danych o produkcie po kodzie kreskowym. Kolejność wywoływania
 * dostawców ustala konfiguracja (`crm.products.resolution.order`), nie kod — dokładnie
 * jak w module `gus` (port + adaptery do rejestru zewnętrznego).
 */
interface ProductDataProvider {
    val source: ProductSource

    /** Czy dostawca jest w ogóle włączony (np. GS1 bez umowy licencyjnej — false). */
    val enabled: Boolean get() = true

    suspend fun findByGtin(gtin: Gtin): ProductLookupResult?
}

data class ProductLookupResult(
    val spec: ProductSpec,
    val source: ProductSource,
    /** 0.0–1.0. Rejestry zwracają 1.0; wartość pośrednią zwraca tylko dostawca AI. */
    val confidence: Double,
    /** Surowa odpowiedź dostawcy — do audytu i ewentualnego reprocessingu. */
    val rawPayload: String?
)
