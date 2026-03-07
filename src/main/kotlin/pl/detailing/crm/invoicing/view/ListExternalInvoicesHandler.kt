package pl.detailing.crm.invoicing.view

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.ExternalInvoice
import pl.detailing.crm.invoicing.domain.InvoicingCredentialsNotFoundException
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
 * Lists invoices from the local cache (no API call to provider).
 * Use [pl.detailing.crm.invoicing.sync.SyncInvoiceStatusHandler] to refresh statuses.
 */
@Service
class ListExternalInvoicesHandler(
    private val invoiceRepository: ExternalInvoiceRepository,
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {

    fun handle(query: ListExternalInvoicesQuery): ExternalInvoiceListResult {
        val credentials = credentialsRepository.findByStudioId(query.studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()

        val provider = providerRegistry.getProvider(credentials.provider)

        val pageable = PageRequest.of(
            query.page - 1,
            query.pageSize,
            Sort.by(Sort.Direction.DESC, "issueDate", "createdAt")
        )

        val page = invoiceRepository.findByStudioIdAndProvider(
            query.studioId.value,
            credentials.provider,
            pageable
        )

        val invoices = page.content.map { entity ->
            entity.toDomain(provider.getInvoicePortalUrl(entity.externalId))
        }

        return ExternalInvoiceListResult(
            invoices = invoices,
            total    = page.totalElements,
            page     = query.page,
            pageSize = query.pageSize
        )
    }
}
