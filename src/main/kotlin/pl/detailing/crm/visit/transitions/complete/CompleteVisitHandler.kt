package pl.detailing.crm.visit.transitions.complete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.LocalDate

@Service
class CompleteVisitHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService,
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler,
    private val capabilityService: CapabilityService
) {
    private val log = LoggerFactory.getLogger(CompleteVisitHandler::class.java)

    @Transactional
    suspend fun handle(command: CompleteVisitCommand): CompleteVisitResult = withContext(Dispatchers.IO) {
        // Completing a visit is a BASIC operation; issuing a financial document is the
        // finance module. An EXPLICIT invoice request without the module is a 402
        // (the UI shows the upsell instead of the invoice form); the default receipt
        // auto-issue degrades to "close without a document" — see issueFinancialDocument.
        if (command.documentType == DocumentType.INVOICE) {
            capabilityService.requireCapability(command.studioId, CapabilityKey.FINANCE_INVOICE_ISSUE)
        }

        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        visitEntity.serviceItems.size

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

        when (command.documentType) {
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
     * and APPROVED service items. Returns null for free visits (total gross = 0) or when
     * there are no service items.
     */
    private fun issueFinancialDocument(
        command: CompleteVisitCommand,
        visit: Visit,
        customer: CustomerEntity?
    ): FinancialDocument? {
        // Without the finance module the studio has no finance views — creating
        // documents it can never see would only corrupt future bookkeeping. The visit
        // still completes; the response carries financialDocumentId = null and the
        // frontend's "close without invoice" path owns the UX.
        if (!capabilityService.hasCapability(command.studioId, CapabilityKey.FINANCE_INVOICE_ISSUE)) {
            log.info(
                "Visit {} completed without financial document — FINANCE module not entitled for studio={}",
                command.visitId, command.studioId
            )
            return null
        }
        if (visit.serviceItems.isEmpty()) {
            log.info("Visit {} has no service items – skipping financial document creation", command.visitId)
            return null
        }
        if (visit.isFreeVisit()) {
            log.info("Visit {} is a free visit (total gross = 0) – skipping financial document creation", command.visitId)
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
                totalNet          = command.documentTotalsOverride?.net ?: visit.calculateTotalNet().amountInCents,
                totalVat          = command.documentTotalsOverride?.vat ?: visit.calculateTotalVat().amountInCents,
                totalGross        = command.documentTotalsOverride?.gross ?: visit.calculateTotalGross().amountInCents,
                currency          = "PLN",
                issueDate         = LocalDate.now(),
                dueDate           = command.dueDate ?: LocalDate.now().plusDays(14),
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
    val dueDate: LocalDate? = null,

    /**
     * Kwoty dokumentu finansowego inne niż suma usług wizyty — używane, gdy faktura
     * pokrywa tylko część kwoty wizyty (reszta jest dokumentowana osobnym dokumentem
     * przez [CompleteVisitInvoiceOrchestrator]). Null → kwoty liczone z usług wizyty.
     */
    val documentTotalsOverride: DocumentTotals? = null
)

/** Kwoty dokumentu w groszach; niezmiennik net + vat == gross. */
data class DocumentTotals(val net: Long, val vat: Long, val gross: Long)

data class CompleteVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus,
    val completedAt: java.time.Instant,

    /** ID of the auto-created financial document (RECEIPT type). Null for INVOICE type or no service items. */
    val financialDocumentId: FinancialDocumentId?,

    /** Human-readable document number, e.g. "PAR/2024/0001". Null for INVOICE type. */
    val financialDocumentNumber: String?
)
