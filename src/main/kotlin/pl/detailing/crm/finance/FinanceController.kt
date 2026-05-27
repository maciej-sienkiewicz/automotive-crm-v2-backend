package pl.detailing.crm.finance

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
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
import pl.detailing.crm.finance.domain.CashRegister
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.finance.reporting.FinanceReportQuery
import pl.detailing.crm.finance.reporting.FinanceReportingHandler
import pl.detailing.crm.finance.reporting.PaymentMethodReportHandler
import pl.detailing.crm.finance.reporting.PaymentMethodReportQuery
import pl.detailing.crm.finance.reporting.ReportGranularity
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
    private val reportingHandler: FinanceReportingHandler,
    private val paymentMethodReportHandler: PaymentMethodReportHandler,
    private val auditService: AuditService
) {

    // ── Income Records (Dokumenty Przychodowe) ────────────────────────────────

    /**
     * Create an income record (marks that a visit generated an external invoice).
     * POST /api/v1/finance/documents
     */
    @PostMapping("/documents")
    fun createDocument(@RequestBody request: CreateDocumentRequest): ResponseEntity<FinancialDocumentResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()

        val result = createDocumentHandler.handle(
            CreateFinancialDocumentCommand(
                studioId          = principal.studioId,
                userId            = principal.userId,
                userDisplayName   = principal.fullName,
                source            = DocumentSource.MANUAL,
                visitId           = request.visitId?.let { VisitId(it) },
                vehicleBrand      = request.vehicleBrand,
                vehicleModel      = request.vehicleModel,
                customerFirstName = request.customerFirstName,
                customerLastName  = request.customerLastName,
                documentType      = parseEnum<DocumentType>(request.documentType, "documentType"),
                direction         = parseEnum<DocumentDirection>(request.direction, "direction"),
                paymentMethod     = parseEnum<PaymentMethod>(request.paymentMethod, "paymentMethod"),
                totalNet          = request.totalNet,
                totalVat          = request.totalVat,
                totalGross        = request.totalGross,
                currency          = request.currency ?: "PLN",
                issueDate         = request.issueDate,
                dueDate           = request.dueDate ?: LocalDate.now().plusDays(14),
                description       = request.description,
                counterpartyName  = request.counterpartyName,
                counterpartyNip   = request.counterpartyNip
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponse())
    }

    /**
     * Update the document number of an income record (e.g. after assigning it in an external system).
     * PATCH /api/v1/finance/documents/{id}
     */
    @PatchMapping("/documents/{id}")
    @Transactional
    fun updateDocumentNumber(
        @PathVariable id: UUID,
        @RequestBody request: UpdateDocumentNumberRequest
    ): ResponseEntity<FinancialDocumentResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()

        val trimmed = request.documentNumber.trim()
        if (trimmed.isBlank()) throw ValidationException("Numer dokumentu nie może być pusty")
        if (trimmed.length > 100) throw ValidationException("Numer dokumentu nie może przekraczać 100 znaków")

        val entity = documentRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy $id nie istnieje")

        val oldNumber = entity.documentNumber
        entity.documentNumber = trimmed
        entity.updatedBy      = principal.userId.value
        entity.updatedAt      = Instant.now()
        documentRepository.save(entity)

        auditService.logSync(
            LogAuditCommand(
                studioId          = principal.studioId,
                userId            = principal.userId,
                userDisplayName   = principal.fullName,
                module            = AuditModule.FINANCE,
                entityId          = id.toString(),
                entityDisplayName = trimmed,
                action            = AuditAction.DOCUMENT_NUMBER_UPDATED,
                changes           = listOf(FieldChange("documentNumber", oldNumber, trimmed))
            )
        )
        return ResponseEntity.ok(entity.toDomain().toResponse())
    }

    /**
     * List income records with optional filters.
     * GET /api/v1/finance/documents
     */
    @GetMapping("/documents")
    fun listDocuments(
        @RequestParam(required = false) documentType: String?,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) visitId: UUID?,
        @RequestParam(required = false) dateFrom: LocalDate?,
        @RequestParam(required = false) dateTo: LocalDate?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<FinancialDocumentListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listDocumentsHandler.handle(
            ListFinancialDocumentsCommand(
                studioId       = principal.studioId,
                documentType   = documentType?.let { parseEnum<DocumentType>(it, "documentType") },
                direction      = direction?.let    { parseEnum<DocumentDirection>(it, "direction") },
                status         = status?.let       { parseEnum<DocumentStatus>(it, "status") },
                visitId        = visitId?.let { VisitId(it) },
                dateFrom       = dateFrom,
                dateTo         = dateTo,
                includeDeleted = includeDeleted,
                page           = maxOf(1, page),
                pageSize       = size.coerceIn(1, 100)
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

    /** GET /api/v1/finance/documents/{id} */
    @GetMapping("/documents/{id}")
    fun getDocument(@PathVariable id: UUID): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = documentRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy $id nie istnieje")
        return ResponseEntity.ok(entity.toDomain().toResponse())
    }

    /** PATCH /api/v1/finance/documents/{id}/status */
    @PatchMapping("/documents/{id}/status")
    fun updateStatus(
        @PathVariable id: UUID,
        @RequestBody request: UpdateStatusRequest
    ): ResponseEntity<FinancialDocumentResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()

        val result = updateStatusHandler.handle(
            UpdateDocumentStatusCommand(
                studioId        = principal.studioId,
                userId          = principal.userId,
                userDisplayName = principal.fullName,
                documentId      = FinancialDocumentId(id),
                newStatus       = parseEnum<DocumentStatus>(request.status, "status")
            )
        )
        return ResponseEntity.ok(result.toResponse())
    }

    /** Soft-delete. DELETE /api/v1/finance/documents/{id} */
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

    /** POST /api/v1/finance/documents/{id}/restore */
    @PostMapping("/documents/{id}/restore")
    @Transactional
    fun restoreDocument(@PathVariable id: UUID): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel może przywracać dokumenty finansowe")
        }

        val entity = documentRepository.findByIdAndStudioIdIncludingDeleted(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Dokument finansowy $id nie istnieje")

        if (entity.deletedAt == null) throw ValidationException("Dokument $id nie jest usunięty")

        entity.deletedAt = null
        entity.updatedBy = principal.userId.value
        entity.updatedAt = Instant.now()
        val saved = documentRepository.save(entity)

        auditService.logSync(
            LogAuditCommand(
                studioId          = principal.studioId,
                userId            = principal.userId,
                userDisplayName   = principal.fullName,
                module            = AuditModule.FINANCE,
                entityId          = id.toString(),
                entityDisplayName = entity.documentNumber,
                action            = AuditAction.DOCUMENT_RESTORED
            )
        )
        return ResponseEntity.ok(saved.toDomain().toResponse())
    }

    // ── Cash Register ─────────────────────────────────────────────────────────

    @GetMapping("/cash")
    fun getCashRegister(): ResponseEntity<CashRegisterResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(getCashRegisterHandler.getCashRegister(GetCashRegisterQuery(principal.studioId)).toResponse())
    }

    @GetMapping("/cash/history")
    fun getCashHistory(
        @RequestParam(defaultValue = "1")  page: Int,
        @RequestParam(defaultValue = "30") size: Int
    ): ResponseEntity<CashHistoryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getCashRegisterHandler.getCashHistory(
            CashHistoryQuery(studioId = principal.studioId, page = maxOf(1, page), pageSize = size.coerceIn(1, 100))
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

    @PostMapping("/cash/adjust")
    fun adjustCash(@RequestBody request: CashAdjustRequest): ResponseEntity<CashRegisterResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            adjustCashHandler.handle(
                AdjustCashBalanceCommand(
                    studioId        = principal.studioId,
                    userId          = principal.userId,
                    userDisplayName = principal.fullName,
                    amount          = request.amount,
                    comment         = request.comment
                )
            ).toResponse()
        )
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

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
            FinanceReportQuery(studioId = principal.studioId, dateFrom = dateFrom, dateTo = dateTo)
        )
        return ResponseEntity.ok(
            FinanceSummaryResponse(
                dateFrom           = result.dateFrom?.toString(),
                dateTo             = result.dateTo?.toString(),
                totalRevenue       = result.totalRevenue.amountInCents,
                totalCosts         = result.totalCosts.amountInCents,
                profit             = result.profit.amountInCents,
                pendingReceivables = result.pendingReceivables.amountInCents,
                pendingPayables    = result.pendingPayables.amountInCents,
                overdueReceivables = result.overdueReceivables,
                overduePayables    = result.overduePayables
            )
        )
    }

    @GetMapping("/payment-method-report")
    fun getPaymentMethodReport(
        @RequestParam(defaultValue = "MONTHLY") granularity: String,
        @RequestParam(required = false) dateFrom: LocalDate?,
        @RequestParam(required = false) dateTo: LocalDate?,
        @RequestParam(required = false) documentType: String?
    ): ResponseEntity<PaymentMethodReportResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
            throw ValidationException("dateTo nie może być wcześniejsze niż dateFrom")
        }
        val result = paymentMethodReportHandler.getReport(
            PaymentMethodReportQuery(
                studioId     = principal.studioId,
                granularity  = parseEnum<ReportGranularity>(granularity, "granularity"),
                dateFrom     = dateFrom,
                dateTo       = dateTo,
                documentType = documentType?.let { parseEnum<DocumentType>(it, "documentType") }
            )
        )
        return ResponseEntity.ok(
            PaymentMethodReportResponse(
                granularity  = result.granularity.name,
                dateFrom     = result.dateFrom?.toString(),
                dateTo       = result.dateTo?.toString(),
                documentType = result.documentType?.name,
                periods      = result.periods.map { p ->
                    PeriodPaymentStatsResponse(
                        periodLabel = p.periodLabel,
                        dateFrom    = p.dateFrom.toString(),
                        dateTo      = p.dateTo.toString(),
                        cash        = PaymentMethodStatsResponse(p.cash.count, p.cash.totalNet, p.cash.totalGross),
                        card        = PaymentMethodStatsResponse(p.card.count, p.card.totalNet, p.card.totalGross),
                        transfer    = PaymentMethodStatsResponse(p.transfer.count, p.transfer.totalNet, p.transfer.totalGross)
                    )
                },
                totals = PaymentMethodTotalsResponse(
                    cash     = PaymentMethodStatsResponse(result.cash.count, result.cash.totalNet, result.cash.totalGross),
                    card     = PaymentMethodStatsResponse(result.card.count, result.card.totalNet, result.card.totalGross),
                    transfer = PaymentMethodStatsResponse(result.transfer.count, result.transfer.totalNet, result.transfer.totalGross)
                )
            )
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requireManagerOrOwner() {
        val role = SecurityContextHelper.getCurrentUser().role
        if (role != UserRole.OWNER && role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel lub manager może wykonać tę operację")
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, fieldName: String): T =
        runCatching { enumValueOf<T>(value.uppercase()) }.getOrElse {
            throw ValidationException(
                "Nieprawidłowa wartość '$value' dla '$fieldName'. Dozwolone: ${enumValues<T>().joinToString { it.name }}"
            )
        }
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

data class CreateDocumentRequest(
    val documentType: String,
    val direction: String,
    val paymentMethod: String,
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val currency: String? = "PLN",
    val issueDate: LocalDate,
    val dueDate: LocalDate? = LocalDate.now().plusDays(14),
    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?,
    val visitId: UUID? = null,
    val vehicleBrand: String? = null,
    val vehicleModel: String? = null,
    val customerFirstName: String? = null,
    val customerLastName: String? = null
)

data class UpdateDocumentNumberRequest(val documentNumber: String)
data class UpdateStatusRequest(val status: String)
data class CashAdjustRequest(val amount: Long, val comment: String)

// ── Response DTOs ──────────────────────────────────────────────────────────────

data class FinancialDocumentResponse(
    val id: String,
    val documentNumber: String,
    val source: String,
    val sourceLabel: String,
    val documentType: String,
    val documentTypeLabel: String,
    val direction: String,
    val directionLabel: String,
    val status: String,
    val statusLabel: String,
    val paymentMethod: String,
    val paymentMethodLabel: String,
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
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val customerFirstName: String?,
    val customerLastName: String?,
    val createdBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?
)

data class FinancialDocumentListResponse(
    val documents: List<FinancialDocumentResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class CashRegisterResponse(val id: String, val balance: Long, val currency: String, val updatedAt: Instant)

data class CashOperationResponse(
    val id: String,
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
    val totalRevenue: Long,
    val totalCosts: Long,
    val profit: Long,
    val pendingReceivables: Long,
    val pendingPayables: Long,
    val overdueReceivables: Long,
    val overduePayables: Long
)

data class PaymentMethodStatsResponse(val count: Int, val totalNet: Long, val totalGross: Long)

data class PeriodPaymentStatsResponse(
    val periodLabel: String,
    val dateFrom: String,
    val dateTo: String,
    val cash: PaymentMethodStatsResponse,
    val card: PaymentMethodStatsResponse,
    val transfer: PaymentMethodStatsResponse
)

data class PaymentMethodTotalsResponse(
    val cash: PaymentMethodStatsResponse,
    val card: PaymentMethodStatsResponse,
    val transfer: PaymentMethodStatsResponse
)

data class PaymentMethodReportResponse(
    val granularity: String,
    val dateFrom: String?,
    val dateTo: String?,
    val documentType: String?,
    val periods: List<PeriodPaymentStatsResponse>,
    val totals: PaymentMethodTotalsResponse
)

// ── Domain → Response mapping ─────────────────────────────────────────────────

private fun FinancialDocument.toResponse() = FinancialDocumentResponse(
    id                = id.toString(),
    documentNumber    = documentNumber,
    source            = source.name,
    sourceLabel       = source.displayName,
    documentType      = documentType.name,
    documentTypeLabel = documentType.displayName,
    direction         = direction.name,
    directionLabel    = direction.displayName,
    status            = status.name,
    statusLabel       = status.displayName,
    paymentMethod     = paymentMethod.name,
    paymentMethodLabel = paymentMethod.displayName,
    totalNet          = totalNet.amountInCents,
    totalVat          = totalVat.amountInCents,
    totalGross        = totalGross.amountInCents,
    currency          = currency,
    issueDate         = issueDate.toString(),
    dueDate           = dueDate?.toString(),
    paidAt            = paidAt,
    description       = description,
    counterpartyName  = counterpartyName,
    counterpartyNip   = counterpartyNip,
    visitId           = visitId?.toString(),
    vehicleBrand      = vehicleBrand,
    vehicleModel      = vehicleModel,
    customerFirstName = customerFirstName,
    customerLastName  = customerLastName,
    createdBy         = createdBy.toString(),
    createdAt         = createdAt,
    updatedAt         = updatedAt,
    deletedAt         = deletedAt
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
