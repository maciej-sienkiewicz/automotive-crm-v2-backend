package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.invoicing.InvoicingFacade
import pl.detailing.crm.invoicing.domain.ExternalInvoiceSnapshot
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.regex.Pattern

data class ImportProviderInvoicesCommand(val studioId: StudioId)

data class ImportProviderInvoicesResult(
    val imported: Int,
    val updated: Int,
    val merged: Int,
    val failed: Int,
    val errors: List<String>
)

/**
 * Imports all invoices from the configured external provider into [FinancialDocumentEntity].
 *
 * This is the mechanism for pulling in invoices that were created directly in the provider's
 * system (e.g. by the studio's accountant in inFakt) rather than through this CRM.
 *
 * ## Deduplication strategy
 *
 * To prevent the same invoice from being stored twice, the handler applies three checks in order:
 *
 * 1. **By externalId**: Matches on `(studio_id, provider, external_id)` – the primary key.
 *    Existing documents are updated (status, correction flags). New documents are inserted.
 *
 * 2. **By visitId (SYNC_FAILED merge)**: If a provider invoice's notes contain the CRM tag
 *    `[crm:visitId:UUID]` (written there by [IssueVisitInvoiceHandler]) and exactly one
 *    SYNC_FAILED document exists for that visit, the two are merged: the SYNC_FAILED document
 *    is updated with the provider's externalId rather than creating a duplicate.
 *
 * 3. **If no match**: A new document is inserted with source=PROVIDER.
 */
@Service
class ImportProviderInvoicesHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val invoicingFacade: InvoicingFacade
) {
    private val log = LoggerFactory.getLogger(ImportProviderInvoicesHandler::class.java)

    companion object {
        /** Pattern used to extract visitId embedded in provider invoice notes. */
        private val VISIT_ID_PATTERN = Pattern.compile("\\[crm:visitId:([0-9a-f-]{36})\\]")
    }

    /**
     * Triggered automatically after credentials are saved.
     * Runs in a NEW transaction so it is independent from the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handleWithCredentials(studioId: StudioId, provider: InvoiceProviderType, apiKey: String): ImportProviderInvoicesResult {
        log.info("[Import] handleWithCredentials: provider={}, studio={}", provider, studioId.value)
        return doImport(studioId)
    }

    @Transactional
    fun handle(command: ImportProviderInvoicesCommand): ImportProviderInvoicesResult =
        doImport(command.studioId)

    private fun doImport(studioId: StudioId): ImportProviderInvoicesResult {
        val providerData = invoicingFacade.fetchAllFromProvider(studioId)
            ?: return ImportProviderInvoicesResult(0, 0, 0, 0, listOf("Brak skonfigurowanego dostawcy faktur"))

        val (provider, snapshots) = providerData
        log.info("[Import] Fetched {} invoices from provider={} for studio={}", snapshots.size, provider, studioId.value)

        var imported = 0
        var updated  = 0
        var merged   = 0
        var failed   = 0
        val errors   = mutableListOf<String>()
        val now      = Instant.now()

        for (snapshot in snapshots) {
            try {
                val result = upsert(studioId, provider, snapshot, now)
                when (result) {
                    UpsertResult.IMPORTED -> imported++
                    UpsertResult.UPDATED  -> updated++
                    UpsertResult.MERGED   -> merged++
                }
            } catch (ex: Exception) {
                failed++
                val msg = "Błąd importu faktury ${snapshot.externalId}: ${ex.message}"
                errors += msg
                log.warn(msg, ex)
            }
        }

        log.info(
            "[Import] studio={}: zaimportowano={}, zaktualizowano={}, scalono={}, błędy={}",
            studioId.value, imported, updated, merged, failed
        )

        return ImportProviderInvoicesResult(
            imported = imported,
            updated  = updated,
            merged   = merged,
            failed   = failed,
            errors   = errors
        )
    }

    private enum class UpsertResult { IMPORTED, UPDATED, MERGED }

    private fun upsert(
        studioId: StudioId,
        provider: InvoiceProviderType,
        snapshot: ExternalInvoiceSnapshot,
        now: Instant
    ): UpsertResult {
        // 1. Primary deduplication: same provider + externalId
        val existing = documentRepository.findByStudioIdAndProviderAndExternalId(
            studioId.value, provider, snapshot.externalId
        )
        if (existing != null) {
            existing.externalStatus       = snapshot.status
            existing.hasCorrection        = snapshot.hasCorrection
            existing.correctionExternalId = snapshot.correctionExternalId
            existing.syncedAt             = now
            existing.updatedAt            = now
            documentRepository.save(existing)
            return UpsertResult.UPDATED
        }

        // 2. Visit-based deduplication: merge with SYNC_FAILED document from the same visit.
        //    The visitId is embedded in the notes by IssueVisitInvoiceHandler as [crm:visitId:UUID].
        val visitIdFromNotes = parseVisitId(snapshot.notes)
        if (visitIdFromNotes != null) {
            val syncFailed = documentRepository.findSyncFailedByStudioIdAndVisitId(
                studioId.value, visitIdFromNotes
            )
            if (syncFailed.size == 1) {
                val toMerge = syncFailed.first()
                toMerge.externalId           = snapshot.externalId
                toMerge.externalNumber       = snapshot.externalNumber
                toMerge.externalStatus       = snapshot.status
                toMerge.hasCorrection        = snapshot.hasCorrection
                toMerge.correctionExternalId = snapshot.correctionExternalId
                toMerge.providerSyncStatus   = InvoiceProviderSyncStatus.SYNCED
                toMerge.providerSyncError    = null
                toMerge.syncedAt             = now
                toMerge.updatedAt            = now
                documentRepository.save(toMerge)
                log.info(
                    "[Import] Merged SYNC_FAILED document {} with provider invoice externalId={}",
                    toMerge.id, snapshot.externalId
                )
                return UpsertResult.MERGED
            }
        }

        // 3. New document: imported from provider (created outside CRM)
        val internalStatus = deriveInternalStatus(snapshot)
        documentRepository.save(
            FinancialDocumentEntity(
                id                      = UUID.randomUUID(),
                studioId                = studioId.value,
                source                  = DocumentSource.PROVIDER,
                visitId                 = null,
                vehicleBrand            = null,
                vehicleModel            = null,
                customerFirstName       = null,
                customerLastName        = null,
                documentNumber          = generateDocumentNumber(studioId.value, snapshot.issueDate),
                documentType            = DocumentType.INVOICE,
                direction               = DocumentDirection.INCOME,
                status                  = internalStatus,
                paymentMethod           = PaymentMethod.TRANSFER,  // unknown, default to TRANSFER
                totalNet                = snapshot.netAmountInCents,
                totalVat                = snapshot.vatAmountInCents,
                totalGross              = snapshot.grossAmountInCents,
                currency                = snapshot.currency,
                issueDate               = snapshot.issueDate,
                dueDate                 = snapshot.dueDate,
                paidAt                  = if (internalStatus == DocumentStatus.PAID) now else null,
                description             = null,
                counterpartyName        = snapshot.buyerName,
                counterpartyNip         = snapshot.buyerNip,
                provider                = provider,
                externalId              = snapshot.externalId,
                externalNumber          = snapshot.externalNumber,
                externalStatus          = snapshot.status,
                isCorrection            = snapshot.isCorrection,
                hasCorrection           = snapshot.hasCorrection,
                correctionExternalId    = snapshot.correctionExternalId,
                providerSyncStatus      = InvoiceProviderSyncStatus.SYNCED,
                providerSyncAttemptedAt = now,
                syncedAt                = now,
                ksefInvoiceId           = null,
                ksefNumber              = null,
                createdBy               = UUID.fromString("00000000-0000-0000-0000-000000000000"),
                updatedBy               = UUID.fromString("00000000-0000-0000-0000-000000000000"),
                createdAt               = now,
                updatedAt               = now
            )
        )
        return UpsertResult.IMPORTED
    }

    private fun parseVisitId(notes: String?): UUID? {
        if (notes.isNullOrBlank()) return null
        val matcher = VISIT_ID_PATTERN.matcher(notes)
        return if (matcher.find()) {
            runCatching { UUID.fromString(matcher.group(1)) }.getOrNull()
        } else null
    }

    /**
     * Derives the internal [DocumentStatus] from the provider's [ExternalInvoiceStatus].
     * PAID → PAID, OVERDUE → OVERDUE, everything else → PENDING.
     */
    private fun deriveInternalStatus(snapshot: ExternalInvoiceSnapshot): DocumentStatus = when (snapshot.status) {
        pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus.PAID    -> DocumentStatus.PAID
        pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus.OVERDUE -> DocumentStatus.OVERDUE
        else                                                              -> DocumentStatus.PENDING
    }

    private fun generateDocumentNumber(studioId: UUID, issueDate: LocalDate): String {
        val year      = issueDate.year
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd   = LocalDate.of(year + 1, 1, 1)
        val count = documentRepository.countByStudioTypeAndYear(studioId, DocumentType.INVOICE, yearStart, yearEnd)
        val seq   = (count + 1).toString().padStart(4, '0')
        return "FAK/$year/$seq"
    }
}
