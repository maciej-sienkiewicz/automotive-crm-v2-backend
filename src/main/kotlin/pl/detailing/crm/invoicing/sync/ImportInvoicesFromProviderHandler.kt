package pl.detailing.crm.invoicing.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.ExternalInvoiceSnapshot
import pl.detailing.crm.invoicing.domain.InvoicingCredentialsNotFoundException
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceEntity
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class ImportInvoicesCommand(val studioId: StudioId)

data class ImportResult(
    val imported: Int,
    val updated: Int,
    val failed: Int,
    val errors: List<String>
)

/**
 * Imports all invoices from the configured provider into the local database.
 *
 * Invoices that already exist locally (matched by externalId) are updated
 * (status, correction flags). New invoices are inserted.
 *
 * This is the mechanism for pulling in invoices that were created directly
 * in the provider's system rather than through this CRM.
 */
@Service
class ImportInvoicesFromProviderHandler(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val invoiceRepository: ExternalInvoiceRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {
    private val log = LoggerFactory.getLogger(ImportInvoicesFromProviderHandler::class.java)

    @Transactional
    fun handle(command: ImportInvoicesCommand): ImportResult {
        val credentials = credentialsRepository.findByStudioId(command.studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()

        val provider = providerRegistry.getProvider(credentials.provider)
        val snapshots = provider.listAllInvoices(credentials.apiKey)

        var imported = 0
        var updated = 0
        var failed = 0
        val errors = mutableListOf<String>()
        val now = Instant.now()

        for (snapshot in snapshots) {
            try {
                upsert(command.studioId, credentials.provider, snapshot, now)
                    .also { wasNew -> if (wasNew) imported++ else updated++ }
            } catch (ex: Exception) {
                failed++
                val msg = "Błąd importu faktury ${snapshot.externalId}: ${ex.message}"
                errors += msg
                log.warn(msg, ex)
            }
        }

        log.info(
            "Import faktur dla studia {}: zaimportowano={}, zaktualizowano={}, błędy={}",
            command.studioId.value, imported, updated, failed
        )

        return ImportResult(imported = imported, updated = updated, failed = failed, errors = errors)
    }

    /** @return true if a new record was created, false if an existing one was updated. */
    private fun upsert(
        studioId: StudioId,
        provider: pl.detailing.crm.invoicing.domain.InvoiceProviderType,
        snapshot: ExternalInvoiceSnapshot,
        now: Instant
    ): Boolean {
        val existing = invoiceRepository.findByStudioIdAndProviderAndExternalId(
            studioId.value, provider, snapshot.externalId
        )

        if (existing != null) {
            existing.status               = snapshot.status
            existing.hasCorrection        = snapshot.hasCorrection
            existing.correctionExternalId = snapshot.correctionExternalId
            existing.syncedAt             = now
            existing.updatedAt            = now
            invoiceRepository.save(existing)
            return false
        }

        invoiceRepository.save(
            ExternalInvoiceEntity(
                studioId             = studioId.value,
                provider             = provider,
                externalId           = snapshot.externalId,
                externalNumber       = snapshot.externalNumber,
                status               = snapshot.status,
                isCorrection         = snapshot.isCorrection,
                hasCorrection        = snapshot.hasCorrection,
                correctionExternalId = snapshot.correctionExternalId,
                grossAmount          = snapshot.grossAmountInCents,
                netAmount            = snapshot.netAmountInCents,
                vatAmount            = snapshot.vatAmountInCents,
                currency             = snapshot.currency,
                issueDate            = snapshot.issueDate,
                dueDate              = snapshot.dueDate,
                buyerName            = snapshot.buyerName,
                buyerNip             = snapshot.buyerNip,
                description          = null,
                syncedAt             = now
            )
        )
        return true
    }
}
