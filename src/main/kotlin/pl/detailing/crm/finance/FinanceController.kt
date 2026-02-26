package pl.detailing.crm.finance

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.finance.cash.AdjustCashBalanceCommand
import pl.detailing.crm.finance.cash.AdjustCashBalanceHandler
import pl.detailing.crm.finance.cash.CashHistoryQuery
import pl.detailing.crm.finance.cash.GetCashRegisterHandler
import pl.detailing.crm.finance.cash.GetCashRegisterQuery
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.document.ListFinancialDocumentsCommand
import pl.detailing.crm.finance.document.ListFinancialDocumentsHandler
import pl.detailing.crm.finance.document.UpdateDocumentStatusCommand
import pl.detailing.crm.finance.document.UpdateDocumentStatusHandler
import pl.detailing.crm.finance.domain.CashOperation
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.finance.domain.CashRegister
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.finance.reporting.FinanceReportQuery
import pl.detailing.crm.finance.reporting.FinanceReportingHandler
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/finance")
class FinanceController(
    private val createDocumentHandler: CreateFinancialDocumentHandler,
    private val listDocumentsHandler: ListFinancialDocumentsHandler,
    private val updateStatusHandler: UpdateDocumentStatusHandler,
    private val documentRepository: FinancialDocumentRepository,
    private val adjustCashHandler: AdjustCashBalanceHandler,
    private val getCashRegisterHandler: GetCashRegisterHandler,
    private val reportingHandler: FinanceReportingHandler
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Financial Documents
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Issue a new financial document.
     * POST /api/v1/finance/documents
     *
     * For CASH payments the cash-register balance is updated atomically.
     * For CARD payments the document is immediately PAID with no cash effect.
     * For TRANSFER payments the document starts as PENDING; [dueDate] is required.
     */
    @PostMapping("/documents")
    fun createDocument(
        @RequestBody request: CreateDocumentRequest
    ): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "wystawiać dokumenty finansowe")

        val documentType = parseEnum<DocumentType>(request.documentType, "documentType")
        val direction    = parseEnum<DocumentDirection>(request.direction, "direction")
        val paymentMethod = parseEnum<PaymentMethod>(request.paymentMethod, "paymentMethod")

        val result = createDocumentHandler.handle(
            CreateFinancialDocumentCommand(
                studioId         = principal.studioId,
                userId           = principal.userId,
                userDisplayName  = principal.fullName,
                visitId          = request.visitId?.let { VisitId(it) },
                documentType     = documentType,
                direction        = direction,
                paymentMethod    = paymentMethod,
                totalNet         = request.totalNet,
                totalVat         = request.totalVat,
                totalGross       = request.totalGross,
                currency         = request.currency ?: "PLN",
                issueDate        = request.issueDate,
                dueDate          = request.dueDate,
                description      = request.description,
                counterpartyName = request.counterpartyName,
                counterpartyNip  = request.counterpartyNip
            )
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponse())
    }

    /**
     * List financial documents with optional filters.
     * GET /api/v1/finance/documents?documentType=INVOICE&direction=INCOME&status=PENDING&page=1&size=20
     */
    @GetMapping("/documents")
    fun listDocuments(
        @RequestParam(required = false) documentType: String?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) visitId: UUID?,
        @RequestParam(required = false) dateFrom: LocalDate?,
        @RequestParam(required = false) dateTo: LocalDate?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<FinancialDocumentListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listDocumentsHandler.handle(
            ListFinancialDocumentsCommand(
                studioId     = principal.studioId,
                documentType = documentType?.let { parseEnum(it, "documentType") },
                direction    = direction?.let    { parseEnum(it, "direction") },
                status       = status?.let       { parseEnum(it, "status") },
                visitId      = visitId?.let { VisitId(it) },
                dateFrom     = dateFrom,
                dateTo       = dateTo,
                page         = maxOf(1, page),
                pageSize     = size.coerceIn(1, 100)
            )
        )

        return ResponseEntity.ok(
            FinancialDocumentListResponse(
                documents = result.documents.map { it.toResponse() },
                total     = result.total,
                page      = result.page,
                pageSize  = result.pageSize
            )
        )
    }

    /**
     * Retrieve a single document.
     * GET /api/v1/finance/documents/{id}
     */
    @GetMapping("/documents/{id}")
    fun getDocument(@PathVariable id: UUID): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val entity = documentRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy $id nie istnieje")

        return ResponseEntity.ok(entity.toDomain().toResponse())
    }

    /**
     * Update the payment status of a document.
     * PATCH /api/v1/finance/documents/{id}/status
     *
     * Allowed transitions: PENDING → PAID, PENDING → OVERDUE, OVERDUE → PAID.
     */
    @PatchMapping("/documents/{id}/status")
    fun updateStatus(
        @PathVariable id: UUID,
        @RequestBody request: UpdateStatusRequest
    ): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "zmieniać status dokumentu")

        val newStatus = parseEnum<DocumentStatus>(request.status, "status")

        val result = updateStatusHandler.handle(
            UpdateDocumentStatusCommand(
                studioId        = principal.studioId,
                userId          = principal.userId,
                userDisplayName = principal.fullName,
                documentId      = FinancialDocumentId(id),
                newStatus       = newStatus
            )
        )

        return ResponseEntity.ok(result.toResponse())
    }

    /**
     * Soft-delete a document.
     * DELETE /api/v1/finance/documents/{id}
     *
     * The document is retained in the database for compliance purposes.
     * Only OWNER can delete financial documents.
     */
    @DeleteMapping("/documents/{id}")
    @Transactional
    fun deleteDocument(@PathVariable id: UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może usuwać dokumenty finansowe")
        }

        val entity = documentRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy $id nie istnieje")

        entity.deletedAt = Instant.now()
        entity.updatedBy = principal.userId.value
        entity.updatedAt = Instant.now()
        documentRepository.save(entity)

        return ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cash Register
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get the current cash-register state.
     * GET /api/v1/finance/cash
     */
    @GetMapping("/cash")
    fun getCashRegister(): ResponseEntity<CashRegisterResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val cashRegister = getCashRegisterHandler.getCashRegister(
            GetCashRegisterQuery(principal.studioId)
        )

        return ResponseEntity.ok(cashRegister.toResponse())
    }

    /**
     * Paginated history of all cash operations.
     * GET /api/v1/finance/cash/history?page=1&size=30
     */
    @GetMapping("/cash/history")
    fun getCashHistory(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "30") size: Int
    ): ResponseEntity<CashHistoryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = getCashRegisterHandler.getCashHistory(
            CashHistoryQuery(
                studioId = principal.studioId,
                page     = maxOf(1, page),
                pageSize = size.coerceIn(1, 100)
            )
        )

        return ResponseEntity.ok(
            CashHistoryResponse(
                operations = result.operations.map { it.toResponse() },
                total      = result.total,
                page       = result.page,
                pageSize   = result.pageSize
            )
        )
    }

    /**
     * Perform a manual cash-register adjustment.
     * POST /api/v1/finance/cash/adjust
     *
     * Use for: start-of-day float, withdrawals, deposits, discrepancy corrections.
     * A non-blank [comment] is mandatory for full auditability.
     */
    @PostMapping("/cash/adjust")
    fun adjustCash(
        @RequestBody request: CashAdjustRequest
    ): ResponseEntity<CashRegisterResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "korygować stan kasy")

        val result = adjustCashHandler.handle(
            AdjustCashBalanceCommand(
                studioId        = principal.studioId,
                userId          = principal.userId,
                userDisplayName = principal.fullName,
                amount          = request.amount,
                comment         = request.comment
            )
        )

        return ResponseEntity.ok(result.toResponse())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reporting
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * High-level financial summary.
     * GET /api/v1/finance/summary?dateFrom=2024-01-01&dateTo=2024-12-31
     *
     * All monetary values in the response are in grosz (1/100 PLN).
     * Both date params are optional; omit for all-time totals.
     */
    @GetMapping("/summary")
    fun getSummary(
        @RequestParam(required = false) dateFrom: LocalDate?,
        @RequestParam(required = false) dateTo: LocalDate?
    ): ResponseEntity<FinanceSummaryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
            throw ValidationException("dateTo nie może być wcześniejsze niż dateFrom")
        }

        val result = reportingHandler.getSummary(
            FinanceReportQuery(
                studioId = principal.studioId,
                dateFrom = dateFrom,
                dateTo   = dateTo
            )
        )

        return ResponseEntity.ok(
            FinanceSummaryResponse(
                dateFrom             = result.dateFrom?.toString(),
                dateTo               = result.dateTo?.toString(),
                totalRevenue         = result.totalRevenue.amountInCents,
                totalCosts           = result.totalCosts.amountInCents,
                profit               = result.profit.amountInCents,
                pendingReceivables   = result.pendingReceivables.amountInCents,
                pendingPayables      = result.pendingPayables.amountInCents,
                overdueReceivables   = result.overdueReceivables,
                overduePayables      = result.overduePayables
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun requireManagerOrOwner(role: UserRole, action: String) {
        if (role != UserRole.OWNER && role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel lub manager może $action")
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T {
        return runCatching { enumValueOf<T>(value.uppercase()) }.getOrElse {
            throw ValidationException(
                "Nieprawidłowa wartość '$value' dla pola '$fieldName'. " +
                "Dozwolone: ${enumValues<T>().joinToString { it.name }}"
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class CreateDocumentRequest(
    /** RECEIPT | INVOICE | OTHER */
    val documentType: String,

    /** INCOME | EXPENSE */
    val direction: String,

    /** CASH | CARD | TRANSFER */
    val paymentMethod: String,

    /** Net amount in grosz (1/100 PLN). */
    val totalNet: Long,

    /** VAT amount in grosz. Must satisfy: totalNet + totalVat == totalGross. */
    val totalVat: Long,

    /** Gross amount in grosz. */
    val totalGross: Long,

    /** ISO-4217 currency code, defaults to "PLN". */
    val currency: String? = "PLN",

    val issueDate: LocalDate,

    /** Required when paymentMethod == TRANSFER. */
    val dueDate: LocalDate?,

    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?,

    /** Optional: UUID of the visit this document relates to. */
    val visitId: UUID? = null
)

data class UpdateStatusRequest(
    /** PAID | PENDING | OVERDUE */
    val status: String
)

/**
 * Manual cash-register adjustment request.
 *
 * [amount] in grosz, signed:
 *   positive = deposit / opening float
 *   negative = withdrawal / bank transfer out
 *
 * [comment] is mandatory (e.g. "Otwarcie kasy – stan początkowy 500 PLN").
 */
data class CashAdjustRequest(
    val amount: Long,
    val comment: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class FinancialDocumentResponse(
    val id: String,
    val documentNumber: String,
    val documentType: String,
    val documentTypeLabel: String,
    val direction: String,
    val directionLabel: String,
    val status: String,
    val statusLabel: String,
    val paymentMethod: String,
    val paymentMethodLabel: String,

    /** All monetary values in grosz (1/100 PLN). */
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val currency: String,

    val issueDate: String,
    val dueDate: String?,
    val paidAt: Instant?,

    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?,

    val visitId: String?,

    // KSeF placeholders
    val ksefInvoiceId: String?,
    val ksefNumber: String?,

    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class FinancialDocumentListResponse(
    val documents: List<FinancialDocumentResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class CashRegisterResponse(
    val id: String,
    /** Current balance in grosz (1/100 PLN). */
    val balance: Long,
    val currency: String,
    val updatedAt: Instant
)

data class CashOperationResponse(
    val id: String,
    /** Signed amount in grosz. Positive = in, negative = out. */
    val amount: Long,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val operationType: String,
    val operationTypeLabel: String,
    val comment: String?,
    val financialDocumentId: String?,
    val createdBy: String,
    val createdAt: Instant
)

data class CashHistoryResponse(
    val operations: List<CashOperationResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class FinanceSummaryResponse(
    val dateFrom: String?,
    val dateTo: String?,

    /** Total settled revenue in grosz (INCOME + PAID). */
    val totalRevenue: Long,

    /** Total settled costs in grosz (EXPENSE + PAID). */
    val totalCosts: Long,

    /** Revenue − Costs (always ≥ 0 in this response; check overduePayables for full picture). */
    val profit: Long,

    /** Sum of INCOME PENDING documents – money owed to us. */
    val pendingReceivables: Long,

    /** Sum of EXPENSE PENDING documents – money we owe. */
    val pendingPayables: Long,

    /** Count of INCOME OVERDUE documents. */
    val overdueReceivables: Long,

    /** Count of EXPENSE OVERDUE documents. */
    val overduePayables: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Domain → Response mapping extensions
// ─────────────────────────────────────────────────────────────────────────────

private fun FinancialDocument.toResponse() = FinancialDocumentResponse(
    id                 = id.toString(),
    documentNumber     = documentNumber,
    documentType       = documentType.name,
    documentTypeLabel  = documentType.displayName,
    direction          = direction.name,
    directionLabel     = direction.displayName,
    status             = status.name,
    statusLabel        = status.displayName,
    paymentMethod      = paymentMethod.name,
    paymentMethodLabel = paymentMethod.displayName,
    totalNet           = totalNet.amountInCents,
    totalVat           = totalVat.amountInCents,
    totalGross         = totalGross.amountInCents,
    currency           = currency,
    issueDate          = issueDate.toString(),
    dueDate            = dueDate?.toString(),
    paidAt             = paidAt,
    description        = description,
    counterpartyName   = counterpartyName,
    counterpartyNip    = counterpartyNip,
    visitId            = visitId?.toString(),
    ksefInvoiceId      = ksefInvoiceId?.toString(),
    ksefNumber         = ksefNumber,
    createdBy          = createdBy.toString(),
    createdAt          = createdAt,
    updatedAt          = updatedAt
)

private fun CashRegister.toResponse() = CashRegisterResponse(
    id        = id.toString(),
    balance   = balance.amountInCents,
    currency  = currency,
    updatedAt = updatedAt
)

private fun CashOperation.toResponse() = CashOperationResponse(
    id                  = id.toString(),
    amount              = amount,
    balanceBefore       = balanceBefore.amountInCents,
    balanceAfter        = balanceAfter.amountInCents,
    operationType       = operationType.name,
    operationTypeLabel  = operationType.displayName,
    comment             = comment,
    financialDocumentId = financialDocumentId?.toString(),
    createdBy           = createdBy.toString(),
    createdAt           = createdAt
)
