package pl.detailing.crm.invoicing.view

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.*
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class GetExternalInvoiceQuery(
    val studioId: StudioId,
    val invoiceId: UUID
)

/**
 * Fetches a single invoice by its local ID.
 * Refreshes the status from the provider's API before returning,
 * so the caller always sees the current state.
 */
@Service
class GetExternalInvoiceHandler(
    private val invoiceRepository: ExternalInvoiceRepository,
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {

    @Transactional
    fun handle(query: GetExternalInvoiceQuery): ExternalInvoice {
        val entity = invoiceRepository.findByStudioIdAndId(query.studioId.value, query.invoiceId)
            ?: throw EntityNotFoundException("Faktura o ID ${query.invoiceId} nie istnieje")

        val credentials = credentialsRepository.findByStudioId(query.studioId.value)
            ?: throw InvoicingCredentialsNotFoundException(entity.provider)

        val provider = providerRegistry.getProvider(entity.provider)

        val snapshot = provider.getInvoice(credentials.apiKey, entity.externalId)

        entity.status               = snapshot.status
        entity.hasCorrection        = snapshot.hasCorrection
        entity.correctionExternalId = snapshot.correctionExternalId
        entity.syncedAt             = Instant.now()
        entity.updatedAt            = Instant.now()

        val saved = invoiceRepository.save(entity)
        return saved.toDomain(provider.getInvoicePortalUrl(entity.externalId))
    }
}
