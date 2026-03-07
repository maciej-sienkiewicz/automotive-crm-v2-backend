package pl.detailing.crm.invoicing.view

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.ExternalInvoice
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.StudioId

data class ListExternalInvoicesQuery(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20
)

data class ExternalInvoiceListResult(
    val invoices: List<ExternalInvoice>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

/**
 * Lists invoices from the local store (no provider API call).
 *
 * Returns all invoices for the studio regardless of sync status –
 * including SYNC_FAILED invoices that have not yet been confirmed by the provider.
 *
 * Portal URLs are computed only when credentials are available and the invoice has an externalId.
 */
@Service
class ListExternalInvoicesHandler(
    private val invoiceRepository: ExternalInvoiceRepository,
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {

    fun handle(query: ListExternalInvoicesQuery): ExternalInvoiceListResult {
        val pageable = PageRequest.of(query.page - 1, query.pageSize)

        val page = invoiceRepository.findByStudioIdOrderByIssueDateDescCreatedAtDesc(
            query.studioId.value,
            pageable
        )

        val credentials = credentialsRepository.findByStudioId(query.studioId.value)
        val provider = credentials?.let {
            runCatching { providerRegistry.getProvider(it.provider) }.getOrNull()
        }

        val invoices = page.content.map { entity ->
            val portalUrl = if (provider != null && entity.externalId != null) {
                provider.getInvoicePortalUrl(entity.externalId!!)
            } else null
            entity.toDomain(portalUrl)
        }

        return ExternalInvoiceListResult(
            invoices = invoices,
            total    = page.totalElements,
            page     = query.page,
            pageSize = query.pageSize
        )
    }
}
