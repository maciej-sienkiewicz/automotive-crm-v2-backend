package pl.detailing.crm.invoicing.view

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.ExternalInvoice
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
 *
 * If the invoice has been synced with the provider (externalId is set and credentials exist),
 * the status is refreshed from the provider's API before returning.
 *
 * For SYNC_FAILED invoices (no externalId) the stored data is returned as-is.
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

        val externalId = entity.externalId
        val provider   = entity.provider

        // Only refresh from provider when we have both an externalId and matching credentials
        if (externalId != null && provider != null) {
            val credentials = credentialsRepository.findByStudioId(query.studioId.value)
            if (credentials != null && credentials.provider == provider) {
                runCatching {
                    val adapter  = providerRegistry.getProvider(provider)
                    val snapshot = adapter.getInvoice(credentials.apiKey, externalId)

                    entity.status               = snapshot.status
                    entity.hasCorrection        = snapshot.hasCorrection
                    entity.correctionExternalId = snapshot.correctionExternalId
                    entity.syncedAt             = Instant.now()
                    entity.updatedAt            = Instant.now()
                    invoiceRepository.save(entity)

                    return entity.toDomain(adapter.getInvoicePortalUrl(externalId))
                }
                // Provider sync failure is non-fatal – fall through and return stored data
            }
        }

        val portalUrl = if (provider != null && externalId != null) {
            runCatching { providerRegistry.getProvider(provider).getInvoicePortalUrl(externalId) }.getOrNull()
        } else null

        return entity.toDomain(portalUrl)
    }
}
