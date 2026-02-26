package pl.detailing.crm.visit.transitions.complete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.LocalDate

@Service
class CompleteVisitHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService,
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler
) {

    private val log = LoggerFactory.getLogger(CompleteVisitHandler::class.java)

    @Transactional
    suspend fun handle(command: CompleteVisitCommand): CompleteVisitResult {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        // Force load lazy collections within transaction
        visitEntity.serviceItems.size
        visitEntity.photos.size

        val visit = visitEntity.toDomain()

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.complete(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Audit logging
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visit.visitNumber}",
            action = AuditAction.VISIT_COMPLETED,
            changes = listOf(FieldChange("status", visit.status.name, updatedVisit.status.name))
        ))

        // Step 5: Issue financial document atomically in the same transaction
        val financialDocument = issueFinancialDocument(command, updatedVisit)

        return CompleteVisitResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status,
            completedAt = updatedVisit.pickupDate!!,
            financialDocumentId = financialDocument?.id,
            financialDocumentNumber = financialDocument?.documentNumber
        )
    }

    /**
     * Creates a financial document (receipt / invoice) for all CONFIRMED and APPROVED
     * service items of the completed visit.
     *
     * Runs inside the same `@Transactional` boundary as visit completion, so both
     * the visit status update and the document creation succeed or roll back together.
     *
     * When [CompleteVisitCommand.paymentMethod] is [PaymentMethod.CASH] the studio's
     * cash-register balance is updated within the same transaction.
     *
     * Returns null only when the visit has no service items at all (zero-value visits
     * that were cancelled / rejected before any work was done).
     */
    private fun issueFinancialDocument(command: CompleteVisitCommand, visit: Visit): FinancialDocument? {
        if (visit.serviceItems.isEmpty()) {
            log.info("Visit {} has no service items – skipping financial document creation", command.visitId)
            return null
        }

        val totalNet   = visit.calculateTotalNet()
        val totalGross = visit.calculateTotalGross()
        val totalVat   = visit.calculateTotalVat()

        val vehicleLabel = listOfNotNull(
            visit.brandSnapshot,
            visit.modelSnapshot,
            visit.licensePlateSnapshot?.let { "($it)" }
        ).joinToString(" ")

        return createFinancialDocumentHandler.handle(
            CreateFinancialDocumentCommand(
                studioId         = command.studioId,
                userId           = command.userId,
                userDisplayName  = command.userName ?: "",
                visitId          = visit.id,
                documentType     = command.documentType,
                direction        = DocumentDirection.INCOME,
                paymentMethod    = command.paymentMethod,
                totalNet         = totalNet.amountInCents,
                totalVat         = totalVat.amountInCents,
                totalGross       = totalGross.amountInCents,
                currency         = "PLN",
                issueDate        = LocalDate.now(),
                dueDate          = command.dueDate,
                description      = "Wizyta #${visit.visitNumber} – $vehicleLabel",
                counterpartyName = command.counterpartyName,
                counterpartyNip  = command.counterpartyNip
            )
        )
    }
}

/**
 * Command to complete a visit (hand over vehicle to customer).
 *
 * Payment fields drive automatic financial document creation.
 *
 * - [paymentMethod] CASH     → document status = PAID, cash register credited
 * - [paymentMethod] CARD     → document status = PAID, cash register unaffected
 * - [paymentMethod] TRANSFER → document status = PENDING; [dueDate] is required
 *
 * All fields have sensible defaults so existing callers that don't supply them
 * continue to work (CASH receipt with no counterparty data).
 */
data class CompleteVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val userName: String? = null,

    /** Payment method for the automatically issued financial document. Default: CASH. */
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,

    /** Type of the automatically issued financial document. Default: RECEIPT. */
    val documentType: DocumentType = DocumentType.RECEIPT,

    /** Payment due date – mandatory when [paymentMethod] == [PaymentMethod.TRANSFER]. */
    val dueDate: LocalDate? = null,

    /** Name of the buyer to appear on the document (optional). */
    val counterpartyName: String? = null,

    /** NIP of the buyer to appear on the document (optional). */
    val counterpartyNip: String? = null
)

data class CompleteVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus,
    val completedAt: java.time.Instant,

    /** ID of the auto-created financial document; null if the visit had no service items. */
    val financialDocumentId: FinancialDocumentId?,

    /** Human-readable document number, e.g. "PAR/2024/0001". */
    val financialDocumentNumber: String?
)
