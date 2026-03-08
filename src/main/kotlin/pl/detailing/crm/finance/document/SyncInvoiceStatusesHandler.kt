package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.invoicing.InvoicingFacade
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class SyncInvoiceStatusesCommand(
    val studioId: StudioId,
    /**
     * If provided, only this specific invoice is synced.
     * If null, all active (non-terminal) invoices for the studio are synced.
     */
    val documentId: UUID? = null
)

data class SyncInvoiceStatusesResult(
    val synced: Int,
    val failed: Int,
    val errors: List<String>
)

/**
 * Synchronizes invoice statuses (and correction flags) from the provider's API
 * into [pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity].
 *
 * Can sync a single invoice or all active (non-terminal) invoices for a studio.
 * Terminal external statuses (PAID, CANCELLED) are excluded from bulk sync to minimize API calls.
 *
 * Called by:
 * - Background scheduler for periodic bulk sync.
 * - [pl.detailing.crm.finance.FinanceController] on-demand via POST /finance/invoices/sync.
 */
@Service
class SyncInvoiceStatusesHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val invoicingFacade: InvoicingFacade
) {
    private val log = LoggerFactory.getLogger(SyncInvoiceStatusesHandler::class.java)

    @Transactional
    fun handle(command: SyncInvoiceStatusesCommand): SyncInvoiceStatusesResult {
        val credentials = invoicingFacade.findCredentials(command.studioId)
            ?: return SyncInvoiceStatusesResult(0, 0, listOf("Brak skonfigurowanego dostawcy faktur"))

        val (provider, _) = credentials

        val entitiesToSync = if (command.documentId != null) {
            listOfNotNull(
                documentRepository.findByIdAndStudioId(command.documentId, command.studioId.value)
                    ?.takeIf { it.externalId != null && it.provider == provider }
            )
        } else {
            documentRepository.findActiveInvoicesForSync(command.studioId.value, provider)
        }

        var synced = 0
        var failed = 0
        val errors = mutableListOf<String>()
        val now = Instant.now()

        for (entity in entitiesToSync) {
            val externalId = entity.externalId ?: continue
            try {
                val snapshot = invoicingFacade.syncInvoiceStatus(command.studioId, provider, externalId)

                entity.externalStatus       = snapshot.status
                entity.hasCorrection        = snapshot.hasCorrection
                entity.correctionExternalId = snapshot.correctionExternalId
                entity.syncedAt             = now
                entity.updatedAt            = now

                documentRepository.save(entity)
                synced++
            } catch (ex: Exception) {
                failed++
                val msg = "Błąd synchronizacji faktury ${externalId}: ${ex.message}"
                errors += msg
                log.warn(msg, ex)
            }
        }

        return SyncInvoiceStatusesResult(synced = synced, failed = failed, errors = errors)
    }
}
