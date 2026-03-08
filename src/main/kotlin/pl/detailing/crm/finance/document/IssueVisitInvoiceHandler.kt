package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.CashOperationEntity
import pl.detailing.crm.finance.infrastructure.CashOperationRepository
import pl.detailing.crm.finance.infrastructure.CashRegisterEntity
import pl.detailing.crm.finance.infrastructure.CashRegisterRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.invoicing.InvoicingFacade
import pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.InvoiceItem
import pl.detailing.crm.invoicing.domain.IssueInvoiceRequest
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class IssueVisitInvoiceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val visitNumber: String,

    val buyerName: String,
    val buyerNip: String?,
    val buyerEmail: String?,
    val buyerStreet: String?,
    val buyerCity: String?,
    val buyerPostCode: String?,

    val vehicleBrand: String?,
    val vehicleModel: String?,
    val customerFirstName: String?,
    val customerLastName: String?,

    val items: List<InvoiceItemCommand>,

    /** CASH | CARD | TRANSFER */
    val paymentMethod: PaymentMethod,

    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val currency: String = "PLN",
    val description: String?,

    /** Gross amount in grosz – pre-calculated from visit service items. */
    val grossAmountInCents: Long,
    val netAmountInCents: Long,
    val vatAmountInCents: Long
)

data class InvoiceItemCommand(
    val name: String,
    val quantity: Double,
    val unit: String,
    val unitNetPriceInCents: Long,
    val vatRate: Int
)

/**
 * Issues a VAT invoice when a visit is completed.
 *
 * This handler is the single point of responsibility for creating an invoice financial
 * document and attempting to sync it with the configured external provider (e.g. inFakt).
 *
 * Flow:
 * 1. Generates a sequential invoice document number (FAK/YYYY/NNNN).
 * 2. Attempts to issue the invoice via [InvoicingFacade] (if a provider is configured).
 *    - On success: persists the document with provider fields set and SYNCED status.
 *    - On failure: persists the document with SYNC_FAILED status so it can be retried later
 *      via POST /api/v1/finance/invoices/{id}/retry-sync. The visit completion still succeeds.
 *    - No provider configured: persists the document without provider fields.
 * 3. For CASH payments: updates the studio's cash-register balance atomically.
 *
 * The visitId is embedded in the invoice notes sent to the provider (format: "[crm:visitId:UUID]").
 * This allows [ImportProviderInvoicesHandler] to detect and merge SYNC_FAILED documents with
 * externally-added invoices during import, preventing duplicates.
 */
@Service
class IssueVisitInvoiceHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val cashRegisterRepository: CashRegisterRepository,
    private val cashOperationRepository: CashOperationRepository,
    private val invoicingFacade: InvoicingFacade
) {
    private val log = LoggerFactory.getLogger(IssueVisitInvoiceHandler::class.java)

    @Transactional
    fun handle(command: IssueVisitInvoiceCommand) {
        val now = Instant.now()
        val documentNumber = generateDocumentNumber(command.studioId.value, command.issueDate)
        val paymentStatus = command.paymentMethod.defaultStatus()
        val paidAt = if (paymentStatus == DocumentStatus.PAID) now else null

        val notesWithVisitId = buildNotes(command)
        val providerRequest = buildProviderRequest(command, notesWithVisitId)

        val entity = FinancialDocumentEntity(
            id                = UUID.randomUUID(),
            studioId          = command.studioId.value,
            source            = DocumentSource.VISIT,
            visitId           = command.visitId.value,
            vehicleBrand      = command.vehicleBrand,
            vehicleModel      = command.vehicleModel,
            customerFirstName = command.customerFirstName,
            customerLastName  = command.customerLastName,
            documentNumber    = documentNumber,
            documentType      = DocumentType.INVOICE,
            direction         = DocumentDirection.INCOME,
            status            = paymentStatus,
            paymentMethod     = command.paymentMethod,
            totalNet          = command.netAmountInCents,
            totalVat          = command.vatAmountInCents,
            totalGross        = command.grossAmountInCents,
            currency          = command.currency,
            issueDate         = command.issueDate,
            dueDate           = command.dueDate,
            paidAt            = paidAt,
            description       = command.description,
            counterpartyName  = command.buyerName,
            counterpartyNip   = command.buyerNip,
            ksefInvoiceId     = null,
            ksefNumber        = null,
            createdBy         = command.userId.value,
            updatedBy         = command.userId.value,
            createdAt         = now,
            updatedAt         = now
        )

        tryProviderSync(entity, command, providerRequest, now)

        documentRepository.save(entity)

        if (command.paymentMethod.affectsCashRegister()) {
            recordCashPayment(command, entity.id, documentNumber, now)
        }

        log.info(
            "[Invoice] Visit {} → document {} created. provider={} syncStatus={}",
            command.visitId, documentNumber, entity.provider, entity.providerSyncStatus
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun tryProviderSync(
        entity: FinancialDocumentEntity,
        command: IssueVisitInvoiceCommand,
        request: IssueInvoiceRequest,
        now: Instant
    ) {
        val credentials = invoicingFacade.findCredentials(command.studioId) ?: return

        val (providerType, _) = credentials
        entity.providerSyncAttemptedAt = now

        try {
            val (_, snapshot) = invoicingFacade.issueInvoice(command.studioId, request)

            entity.externalId           = snapshot.externalId
            entity.externalNumber       = snapshot.externalNumber
            entity.externalStatus       = snapshot.status
            entity.isCorrection
            entity.hasCorrection        = snapshot.hasCorrection
            entity.correctionExternalId = snapshot.correctionExternalId
            entity.providerSyncStatus   = InvoiceProviderSyncStatus.SYNCED
            entity.syncedAt             = now

            log.info(
                "[Invoice] Synced to {} for visit {}: externalId={} gross={}",
                providerType, command.visitId, snapshot.externalId, snapshot.grossAmountInCents
            )
        } catch (ex: Exception) {
            entity.providerSyncStatus = InvoiceProviderSyncStatus.SYNC_FAILED
            entity.providerSyncError  = ex.message?.take(2000)

            log.warn(
                "[Invoice] Provider {} call failed for visit {}. Saved locally with SYNC_FAILED. Error: {}",
                providerType, command.visitId, ex.message
            )
        }
    }

    /**
     * Embeds the visitId in the notes as a machine-readable tag.
     * This allows [ImportProviderInvoicesHandler] to detect and merge SYNC_FAILED documents
     * with externally-created provider invoices, preventing duplicates.
     */
    private fun buildNotes(command: IssueVisitInvoiceCommand): String {
        val base = command.description?.takeIf { it.isNotBlank() } ?: "Wizyta #${command.visitNumber}"
        return "$base [crm:visitId:${command.visitId.value}]"
    }

    private fun buildProviderRequest(command: IssueVisitInvoiceCommand, notes: String) = IssueInvoiceRequest(
        buyerName     = command.buyerName,
        buyerNip      = command.buyerNip,
        buyerEmail    = command.buyerEmail,
        buyerStreet   = command.buyerStreet,
        buyerCity     = command.buyerCity,
        buyerPostCode = command.buyerPostCode,
        items         = command.items.map { item ->
            InvoiceItem(
                name                = item.name,
                quantity            = item.quantity,
                unit                = item.unit,
                unitNetPriceInCents = item.unitNetPriceInCents,
                vatRate             = item.vatRate
            )
        },
        paymentMethod = command.paymentMethod.name,
        issueDate     = command.issueDate,
        dueDate       = command.dueDate,
        currency      = command.currency,
        notes         = notes
    )

    private fun generateDocumentNumber(studioId: UUID, issueDate: LocalDate): String {
        val year      = issueDate.year
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd   = LocalDate.of(year + 1, 1, 1)
        val count = documentRepository.countByStudioTypeAndYear(studioId, DocumentType.INVOICE, yearStart, yearEnd)
        val seq   = (count + 1).toString().padStart(4, '0')
        return "FAK/$year/$seq"
    }

    private fun recordCashPayment(
        command: IssueVisitInvoiceCommand,
        documentId: UUID,
        documentNumber: String,
        now: Instant
    ) {
        val cashRegister = cashRegisterRepository.findByStudioIdForUpdate(command.studioId.value)
            ?: cashRegisterRepository.save(
                CashRegisterEntity(studioId = command.studioId.value, balance = 0L)
            )

        val balanceBefore = cashRegister.balance
        val balanceAfter  = balanceBefore + command.grossAmountInCents

        cashRegister.balance   = balanceAfter
        cashRegister.updatedAt = now
        cashRegisterRepository.save(cashRegister)

        cashOperationRepository.save(
            CashOperationEntity(
                id                  = UUID.randomUUID(),
                studioId            = command.studioId.value,
                cashRegisterId      = cashRegister.id,
                amount              = command.grossAmountInCents,
                balanceBefore       = balanceBefore,
                balanceAfter        = balanceAfter,
                operationType       = CashOperationType.PAYMENT_IN,
                comment             = command.description ?: documentNumber,
                financialDocumentId = documentId,
                createdBy           = command.userId.value,
                createdAt           = now
            )
        )

        log.debug(
            "[Invoice] Cash register updated: studio={} before={} amount={} after={}",
            command.studioId, balanceBefore, command.grossAmountInCents, balanceAfter
        )
    }
}
