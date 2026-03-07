package pl.detailing.crm.invoicing.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.InvoicingCredentialsNotFoundException
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class SyncInvoiceStatusCommand(
    val studioId: StudioId,

    /**
     * If provided, only this specific invoice is synced.
     * If null, all non-terminal invoices for the studio are synced.
     */
    val invoiceId: UUID? = null
)

data class SyncResult(
    val synced: Int,
    val failed: Int,
    val errors: List<String>
)

/**
 * Synchronizes invoice statuses (and correction flags) from the provider's API.
 *
 * Can sync a single invoice or all active (non-terminal) invoices for a studio.
 * Terminal statuses (PAID, CANCELLED) are skipped in bulk sync to minimize API calls.
 *
 * Called by:
 * - [SyncInvoiceStatusScheduler] – background job for bulk sync
 * - [pl.detailing.crm.invoicing.InvoicingController] – on-demand single-invoice sync
 */
@Service
class SyncInvoiceStatusHandler(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val invoiceRepository: ExternalInvoiceRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {
    private val log = LoggerFactory.getLogger(SyncInvoiceStatusHandler::class.java)

    @Transactional
    fun handle(command: SyncInvoiceStatusCommand): SyncResult {
        val credentials = credentialsRepository.findByStudioId(command.studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()

        val provider = providerRegistry.getProvider(credentials.provider)

        val entitiesToSync = if (command.invoiceId != null) {
            listOfNotNull(invoiceRepository.findByStudioIdAndId(command.studioId.value, command.invoiceId))
        } else {
            invoiceRepository.findActiveByStudioIdAndProvider(command.studioId.value, credentials.provider)
        }

        var synced = 0
        var failed = 0
        val errors = mutableListOf<String>()

        for (entity in entitiesToSync) {
            val externalId = entity.externalId
            if (externalId == null) {
                // Invoice was never confirmed by provider – skip status sync
                continue
            }
            try {
                val snapshot = provider.syncInvoiceStatus(credentials.apiKey, externalId)

                entity.status               = snapshot.status
                entity.hasCorrection        = snapshot.hasCorrection
                entity.correctionExternalId = snapshot.correctionExternalId
                entity.syncedAt             = Instant.now()
                entity.updatedAt            = Instant.now()

                invoiceRepository.save(entity)
                synced++
            } catch (ex: Exception) {
                failed++
                val msg = "Błąd synchronizacji faktury ${externalId}: ${ex.message}"
                errors += msg
                log.warn(msg, ex)
            }
        }

        return SyncResult(synced = synced, failed = failed, errors = errors)
    }
}
