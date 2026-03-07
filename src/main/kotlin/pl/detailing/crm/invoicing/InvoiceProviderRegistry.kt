package pl.detailing.crm.invoicing

import org.springframework.stereotype.Component
import pl.detailing.crm.invoicing.domain.InvoiceProvider
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.invoicing.domain.InvoicingProviderNotSupportedException

/**
 * Registry that resolves the correct [InvoiceProvider] adapter for a given [InvoiceProviderType].
 *
 * All [InvoiceProvider] Spring beans are injected automatically via the [providers] list.
 * Adding a new provider requires only implementing [InvoiceProvider] and annotating it with
 * [@Component][Component] – no changes needed here.
 */
@Component
class InvoiceProviderRegistry(providers: List<InvoiceProvider>) {

    private val registry: Map<InvoiceProviderType, InvoiceProvider> =
        providers.associateBy { it.type }

    /**
     * Returns the adapter for the given provider type.
     * @throws [InvoicingProviderNotSupportedException] if the provider has no registered adapter.
     */
    fun getProvider(type: InvoiceProviderType): InvoiceProvider =
        registry[type] ?: throw InvoicingProviderNotSupportedException(type)
}
