package pl.detailing.crm.visit.transitions.complete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
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
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService,
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler
) {
    private val log = LoggerFactory.getLogger(CompleteVisitHandler::class.java)

    @Transactional
    suspend fun handle(command: CompleteVisitCommand): CompleteVisitResult {
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        visitEntity.serviceItems.size
        visitEntity.photos.size

        val visit = visitEntity.toDomain()
        val updatedVisit = visit.complete(command.userId)

        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        auditService.log(LogAuditCommand(
            studioId          = command.studioId,
            userId            = command.userId,
            userDisplayName   = command.userName ?: "",
            module            = AuditModule.VISIT,
            entityId          = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visit.visitNumber}",
            action            = AuditAction.VISIT_COMPLETED,
            changes           = listOf(FieldChange("status", visit.status.name, updatedVisit.status.name))
        ))

        val customer = customerRepository.findByIdAndStudioId(visit.customerId.value, command.studioId.value)

        return when (command.documentType) {
            DocumentType.INVOICE -> handleInvoiceCompletion(command, updatedVisit, customer)
            else                 -> handleReceiptCompletion(command, updatedVisit, customer)
        }
    }

    private fun handleInvoiceCompletion(
        command: CompleteVisitCommand,
        visit: Visit,
        customer: CustomerEntity?
    ): CompleteVisitResult {
        val financialDocument = issueFinancialDocument(command, visit, customer)
        return CompleteVisitResult(
            visitId                 = visit.id,
            newStatus               = visit.status,
            completedAt             = visit.pickupDate!!,
            financialDocumentId     = financialDocument?.id,
            financialDocumentNumber = financialDocument?.documentNumber
        )
    }

    /**
     * Completes a visit by issuing a receipt as an internal financial document.
     * CASH payments update the cash register in the same transaction.
     */
    private fun handleReceiptCompletion(
        command: CompleteVisitCommand,
        visit: Visit,
        customer: CustomerEntity?
    ): CompleteVisitResult {
        val financialDocument = issueFinancialDocument(command, visit, customer)
        return CompleteVisitResult(
            visitId                 = visit.id,
            newStatus               = visit.status,
            completedAt             = visit.pickupDate!!,
            financialDocumentId     = financialDocument?.id,
            financialDocumentNumber = financialDocument?.documentNumber
        )
    }

    /**
     * Creates a financial document (receipt or other non-invoice type) for all CONFIRMED
     * and APPROVED service items. Returns null when the visit has no service items.
     */
    private fun issueFinancialDocument(
        command: CompleteVisitCommand,
        visit: Visit,
        customer: CustomerEntity?
    ): FinancialDocument? {
        if (visit.serviceItems.isEmpty()) {
            log.info("Visit {} has no service items – skipping financial document creation", command.visitId)
            return null
        }

        return createFinancialDocumentHandler.handle(
            CreateFinancialDocumentCommand(
                studioId          = command.studioId,
                userId            = command.userId,
                userDisplayName   = command.userName ?: "",
                source            = DocumentSource.VISIT,
                visitId           = visit.id,
                vehicleBrand      = visit.brandSnapshot,
                vehicleModel      = visit.modelSnapshot,
                customerFirstName = customer?.firstName,
                customerLastName  = customer?.lastName,
                documentType      = command.documentType,
                direction         = DocumentDirection.INCOME,
                paymentMethod     = command.paymentMethod,
                totalNet          = visit.calculateTotalNet().amountInCents,
                totalVat          = visit.calculateTotalVat().amountInCents,
                totalGross        = visit.calculateTotalGross().amountInCents,
                currency          = "PLN",
                issueDate         = LocalDate.now(),
                dueDate           = command.dueDate,
                description       = "Wizyta #${visit.visitNumber} – ${buildVehicleLabel(visit)}",
                counterpartyName  = resolveBuyerName(customer),
                counterpartyNip   = customer?.companyNip
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun buildResult(visit: Visit) = CompleteVisitResult(
        visitId                 = visit.id,
        newStatus               = visit.status,
        completedAt             = visit.pickupDate!!,
        financialDocumentId     = null,
        financialDocumentNumber = null
    )

    private fun resolveBuyerName(customer: CustomerEntity?): String? =
        customer?.companyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(customer?.firstName, customer?.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }

    private fun buildVehicleLabel(visit: Visit): String =
        listOfNotNull(
            visit.brandSnapshot,
            visit.modelSnapshot,
            visit.licensePlateSnapshot?.let { "($it)" }
        ).joinToString(" ")
}

/**
 * Command to complete a visit (hand over vehicle to customer).
 *
 * A [FinancialDocument] is created for every document type, including INVOICE.
 * The admin issues the formal VAT invoice in an external program; the CRM record
 * is an internal annotation only.
 */
data class CompleteVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val userName: String? = null,

    val signatureObtained: Boolean = false,

    /** Payment method for the automatically issued financial document. Default: CASH. */
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,

    /** Type of the automatically issued financial document. Default: RECEIPT. */
    val documentType: DocumentType = DocumentType.RECEIPT,

    /** Payment due date – mandatory when [paymentMethod] == [PaymentMethod.TRANSFER]. */
    val dueDate: LocalDate? = null
)

data class CompleteVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus,
    val completedAt: java.time.Instant,

    /** ID of the auto-created financial document (RECEIPT type). Null for INVOICE type or no service items. */
    val financialDocumentId: FinancialDocumentId?,

    /** Human-readable document number, e.g. "PAR/2024/0001". Null for INVOICE type. */
    val financialDocumentNumber: String?
)
