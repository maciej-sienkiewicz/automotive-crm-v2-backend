package pl.detailing.crm.finance

import org.springframework.data.domain.PageRequest
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
import pl.detailing.crm.finance.document.ImportProviderInvoicesCommand
import pl.detailing.crm.finance.document.ImportProviderInvoicesHandler
import pl.detailing.crm.finance.document.ListFinancialDocumentsCommand
import pl.detailing.crm.finance.document.ListFinancialDocumentsHandler
import pl.detailing.crm.finance.document.RetryProviderSyncHandler
import pl.detailing.crm.finance.document.SyncInvoiceStatusesCommand
import pl.detailing.crm.finance.document.SyncInvoiceStatusesHandler
import pl.detailing.crm.finance.document.UpdateDocumentStatusCommand
import pl.detailing.crm.finance.document.UpdateDocumentStatusHandler
import pl.detailing.crm.finance.domain.CashOperation
import pl.detailing.crm.finance.domain.CashOperationType
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
import pl.detailing.crm.invoicing.InvoicingFacade
import pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus
import pl.detailing.crm.invoicing.domain.InvoiceItem
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.invoicing.domain.IssueInvoiceRequest
import pl.detailing.crm.invoicing.domain.InvoicingException
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
    private val syncInvoiceStatusesHandler: SyncInvoiceStatusesHandler,
    private val importProviderInvoicesHandler: ImportProviderInvoicesHandler,
    private val retryProviderSyncHandler: RetryProviderSyncHandler,
    private val invoicingFacade: InvoicingFacade,
    private val adjustCashHandler: AdjustCashBalanceHandler,
    private val getCashRegisterHandler: GetCashRegisterHandler,
    private val reportingHandler: FinanceReportingHandler
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Financial Documents
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Issue a new financial document (receipt, other; NOT a VAT invoice via provider).
     * For VAT invoices with itemized services, use POST /api/v1/finance/invoices.
     * POST /api/v1/finance/documents
     */
    @PostMapping("/documents")
    fun createDocument(
        @RequestBody request: CreateDocumentRequest
    ): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "wystawiać dokumenty finansowe")

        val documentType  = parseEnum<DocumentType>(request.documentType, "documentType")
        val direction     = parseEnum<DocumentDirection>(request.direction, "direction")
        val paymentMethod = parseEnum<PaymentMethod>(request.paymentMethod, "paymentMethod")

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
                documentType      = documentType,
                direction         = direction,
                paymentMethod     = paymentMethod,
                totalNet          = request.totalNet,
                totalVat          = request.totalVat,
                totalGross        = request.totalGross,
                currency          = request.currency ?: "PLN",
                issueDate         = request.issueDate,
                dueDate           = request.dueDate,
                description       = request.description,
                counterpartyName  = request.counterpartyName,
                counterpartyNip   = request.counterpartyNip
            )
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponse(externalUrl = null))
    }

    /**
     * List all financial documents (including VAT invoices).
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
        val pageSize  = size.coerceIn(1, 100)

        val result = listDocumentsHandler.handle(
            ListFinancialDocumentsCommand(
                studioId     = principal.studioId,
                documentType = documentType?.let { parseEnum<DocumentType>(it, "documentType") },
                direction    = direction?.let    { parseEnum<DocumentDirection>(it, "direction") },
                status       = status?.let       { parseEnum<DocumentStatus>(it, "status") },
                visitId      = visitId?.let { VisitId(it) },
                dateFrom     = dateFrom,
                dateTo       = dateTo,
                page         = maxOf(1, page),
                pageSize     = pageSize
            )
        )

        val credentials = invoicingFacade.findCredentials(principal.studioId)

        val documents = result.documents.map { doc ->
            val url = if (credentials != null && doc.provider != null && doc.externalId != null) {
                runCatching { invoicingFacade.getPortalUrl(doc.provider, doc.externalId) }.getOrNull()
            } else null
            doc.toResponse(externalUrl = url)
        }

        return ResponseEntity.ok(
            FinancialDocumentListResponse(
                documents = documents,
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

        val doc = entity.toDomain()
        val url = if (doc.provider != null && doc.externalId != null) {
            runCatching { invoicingFacade.getPortalUrl(doc.provider, doc.externalId) }.getOrNull()
        } else null

        return ResponseEntity.ok(doc.toResponse(externalUrl = url))
    }

    /**
     * Update the payment status of a document.
     * PATCH /api/v1/finance/documents/{id}/status
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

        val url = if (result.provider != null && result.externalId != null) {
            runCatching { invoicingFacade.getPortalUrl(result.provider, result.externalId) }.getOrNull()
        } else null

        return ResponseEntity.ok(result.toResponse(externalUrl = url))
    }

    /**
     * Soft-delete a document.
     * DELETE /api/v1/finance/documents/{id}
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
    // VAT Invoice operations (provider integration)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Issue a new VAT invoice with itemized services via the configured provider.
     * The invoice is persisted as a FinancialDocument (type=INVOICE) and also
     * sent to the external provider (e.g. inFakt) for formal issuance.
     *
     * POST /api/v1/finance/invoices
     */
    @PostMapping("/invoices")
    fun issueInvoice(
        @RequestBody request: IssueInvoiceDocumentRequest
    ): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "wystawiać faktury")

        if (request.items.isEmpty()) {
            throw ValidationException("Faktura musi zawierać co najmniej jedną pozycję")
        }
        val paymentMethod = parseEnum<PaymentMethod>(request.paymentMethod, "paymentMethod")

        val credentials = invoicingFacade.findCredentials(principal.studioId)

        val providerRequest = IssueInvoiceRequest(
            buyerName     = request.buyerName,
            buyerNip      = request.buyerNip,
            buyerEmail    = request.buyerEmail,
            buyerStreet   = request.buyerStreet,
            buyerCity     = request.buyerCity,
            buyerPostCode = request.buyerPostCode,
            items         = request.items.map {
                InvoiceItem(
                    name                = it.name,
                    quantity            = it.quantity,
                    unit                = it.unit,
                    unitNetPriceInCents = it.unitNetPriceInCents,
                    vatRate             = it.vatRate
                )
            },
            paymentMethod = request.paymentMethod,
            issueDate     = request.issueDate,
            dueDate       = request.dueDate,
            currency      = request.currency ?: "PLN",
            notes         = request.notes
        )

        val totalGross = request.items.sumOf { item ->
            val net = item.unitNetPriceInCents * item.quantity
            val vat = if (item.vatRate > 0) net * item.vatRate / 100 else 0.0
            (net + vat).toLong()
        }
        val totalNet = request.items.sumOf { (it.unitNetPriceInCents * it.quantity).toLong() }
        val totalVat = totalGross - totalNet

        val paymentStatus = paymentMethod.defaultStatus()
        val now = Instant.now()
        val year = request.issueDate.year
        val yearStart = LocalDate.of(year, 1, 1)
        val yearEnd   = LocalDate.of(year + 1, 1, 1)
        val count = documentRepository.countByStudioTypeAndYear(
            principal.studioId.value, DocumentType.INVOICE, yearStart, yearEnd
        )
        val documentNumber = "FAK/$year/${(count + 1).toString().padStart(4, '0')}"

        val entity = pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity(
            id                = UUID.randomUUID(),
            studioId          = principal.studioId.value,
            source            = DocumentSource.MANUAL,
            visitId           = null,
            vehicleBrand      = null,
            vehicleModel      = null,
            customerFirstName = null,
            customerLastName  = null,
            documentNumber    = documentNumber,
            documentType      = DocumentType.INVOICE,
            direction         = DocumentDirection.INCOME,
            status            = paymentStatus,
            paymentMethod     = paymentMethod,
            totalNet          = totalNet,
            totalVat          = totalVat,
            totalGross        = totalGross,
            currency          = request.currency ?: "PLN",
            issueDate         = request.issueDate,
            dueDate           = request.dueDate,
            paidAt            = if (paymentStatus == DocumentStatus.PAID) now else null,
            description       = request.notes,
            counterpartyName  = request.buyerName,
            counterpartyNip   = request.buyerNip,
            ksefInvoiceId     = null,
            ksefNumber        = null,
            createdBy         = principal.userId.value,
            updatedBy         = principal.userId.value,
            createdAt         = now,
            updatedAt         = now
        )

        var externalUrl: String? = null
        if (credentials != null) {
            entity.providerSyncAttemptedAt = now
            try {
                val (providerType, snapshot) = invoicingFacade.issueInvoice(principal.studioId, providerRequest)
                entity.provider             = providerType
                entity.externalId           = snapshot.externalId
                entity.externalNumber       = snapshot.externalNumber
                entity.externalStatus       = snapshot.status
                entity.providerSyncStatus   = InvoiceProviderSyncStatus.SYNCED
                entity.syncedAt             = now
                externalUrl = runCatching { invoicingFacade.getPortalUrl(providerType, snapshot.externalId) }.getOrNull()
            } catch (ex: InvoicingException) {
                entity.provider           = credentials.first
                entity.providerSyncStatus = InvoiceProviderSyncStatus.SYNC_FAILED
                entity.providerSyncError  = ex.message?.take(2000)
            }
        }

        val saved = documentRepository.save(entity)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toDomain().toResponse(externalUrl))
    }

    /**
     * Synchronize status of all active invoices from the provider.
     * POST /api/v1/finance/invoices/sync
     */
    @PostMapping("/invoices/sync")
    fun syncAllInvoices(): ResponseEntity<SyncInvoicesResultResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "synchronizować faktury")

        val result = syncInvoiceStatusesHandler.handle(
            SyncInvoiceStatusesCommand(studioId = principal.studioId)
        )

        return ResponseEntity.ok(SyncInvoicesResultResponse(result.synced, result.failed, result.errors))
    }

    /**
     * Synchronize status of a single invoice from the provider.
     * POST /api/v1/finance/invoices/{id}/sync
     */
    @PostMapping("/invoices/{id}/sync")
    fun syncSingleInvoice(@PathVariable id: UUID): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "synchronizować faktury")

        syncInvoiceStatusesHandler.handle(
            SyncInvoiceStatusesCommand(studioId = principal.studioId, documentId = id)
        )

        val entity = documentRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Faktura o ID $id nie istnieje")
        val doc = entity.toDomain()
        val url = if (doc.provider != null && doc.externalId != null) {
            runCatching { invoicingFacade.getPortalUrl(doc.provider, doc.externalId) }.getOrNull()
        } else null

        return ResponseEntity.ok(doc.toResponse(externalUrl = url))
    }

    /**
     * Import all invoices from the configured provider into the local database.
     * POST /api/v1/finance/invoices/import
     */
    @PostMapping("/invoices/import")
    fun importInvoices(): ResponseEntity<ImportInvoicesResultResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "importować faktury")

        val result = importProviderInvoicesHandler.handle(
            ImportProviderInvoicesCommand(studioId = principal.studioId)
        )

        return ResponseEntity.ok(
            ImportInvoicesResultResponse(
                imported = result.imported,
                updated  = result.updated,
                merged   = result.merged,
                failed   = result.failed,
                errors   = result.errors
            )
        )
    }

    /**
     * Retry sending a SYNC_FAILED invoice to the external provider.
     * POST /api/v1/finance/invoices/{id}/retry-sync
     */
    @PostMapping("/invoices/{id}/retry-sync")
    fun retrySyncInvoice(@PathVariable id: UUID): ResponseEntity<FinancialDocumentResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "ponownie synchronizować faktury")

        val doc = retryProviderSyncHandler.handle(studioId = principal.studioId, documentId = id)
        val url = if (doc.provider != null && doc.externalId != null) {
            runCatching { invoicingFacade.getPortalUrl(doc.provider, doc.externalId) }.getOrNull()
        } else null

        return ResponseEntity.ok(doc.toResponse(externalUrl = url))
    }

    /**
     * Get a direct URL to view the invoice on the provider's portal.
     * GET /api/v1/finance/invoices/{id}/portal-url
     */
    @GetMapping("/invoices/{id}/portal-url")
    fun getPortalUrl(@PathVariable id: UUID): ResponseEntity<InvoicePortalUrlResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val entity = documentRepository.findByIdAndStudioId(id, principal.studioId.value)
            ?: throw EntityNotFoundException("Faktura o ID $id nie istnieje")

        val provider   = entity.provider   ?: return ResponseEntity.notFound().build()
        val externalId = entity.externalId ?: return ResponseEntity.notFound().build()

        val url = runCatching { invoicingFacade.getPortalUrl(provider, externalId) }.getOrNull()
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(InvoicePortalUrlResponse(url))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cash Register
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/cash")
    fun getCashRegister(): ResponseEntity<CashRegisterResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            getCashRegisterHandler.getCashRegister(GetCashRegisterQuery(principal.studioId)).toResponse()
        )
    }

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

    @PostMapping("/cash/adjust")
    fun adjustCash(@RequestBody request: CashAdjustRequest): ResponseEntity<CashRegisterResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        requireManagerOrOwner(principal.role, "korygować stan kasy")

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

    // ─────────────────────────────────────────────────────────────────────────
    // Reporting
    // ─────────────────────────────────────────────────────────────────────────

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
    val documentType: String,
    val direction: String,
    val paymentMethod: String,
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val currency: String? = "PLN",
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?,
    val visitId: UUID? = null,
    val vehicleBrand: String? = null,
    val vehicleModel: String? = null,
    val customerFirstName: String? = null,
    val customerLastName: String? = null
)

data class UpdateStatusRequest(val status: String)

data class CashAdjustRequest(val amount: Long, val comment: String)

data class IssueInvoiceDocumentRequest(
    val buyerName: String,
    val buyerNip: String?,
    val buyerEmail: String?,
    val buyerStreet: String?,
    val buyerCity: String?,
    val buyerPostCode: String?,
    val items: List<InvoiceItemRequest>,
    val paymentMethod: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val currency: String? = "PLN",
    val notes: String?
)

data class InvoiceItemRequest(
    val name: String,
    val quantity: Double,
    val unit: String,
    val unitNetPriceInCents: Long,
    val vatRate: Int
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

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

    // ── External provider fields (null for non-invoice/non-synced documents) ─
    val provider: String?,
    val providerLabel: String?,
    val externalId: String?,
    val externalNumber: String?,
    val externalStatus: String?,
    val externalStatusLabel: String?,
    val isCorrection: Boolean,
    val hasCorrection: Boolean,
    val correctionExternalId: String?,
    val providerSyncStatus: String?,
    val providerSyncStatusLabel: String?,
    val providerSyncError: String?,
    val syncedAt: Instant?,
    val externalUrl: String?,

    // ── KSeF placeholders ──────────────────────────────────────────────────
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
    val balance: Long,
    val currency: String,
    val updatedAt: Instant
)

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

data class SyncInvoicesResultResponse(
    val synced: Int,
    val failed: Int,
    val errors: List<String>
)

data class ImportInvoicesResultResponse(
    val imported: Int,
    val updated: Int,
    val merged: Int,
    val failed: Int,
    val errors: List<String>
)

data class InvoicePortalUrlResponse(val url: String)

// ─────────────────────────────────────────────────────────────────────────────
// Domain → Response mapping
// ─────────────────────────────────────────────────────────────────────────────

private fun FinancialDocument.toResponse(externalUrl: String?) = FinancialDocumentResponse(
    id                      = id.toString(),
    documentNumber          = documentNumber,
    source                  = source.name,
    sourceLabel             = source.displayName,
    documentType            = documentType.name,
    documentTypeLabel       = documentType.displayName,
    direction               = direction.name,
    directionLabel          = direction.displayName,
    status                  = status.name,
    statusLabel             = status.displayName,
    paymentMethod           = paymentMethod.name,
    paymentMethodLabel      = paymentMethod.displayName,
    totalNet                = totalNet.amountInCents,
    totalVat                = totalVat.amountInCents,
    totalGross              = totalGross.amountInCents,
    currency                = currency,
    issueDate               = issueDate.toString(),
    dueDate                 = dueDate?.toString(),
    paidAt                  = paidAt,
    description             = description,
    counterpartyName        = counterpartyName,
    counterpartyNip         = counterpartyNip,
    visitId                 = visitId?.toString(),
    vehicleBrand            = vehicleBrand,
    vehicleModel            = vehicleModel,
    customerFirstName       = customerFirstName,
    customerLastName        = customerLastName,
    provider                = provider?.name,
    providerLabel           = provider?.displayName,
    externalId              = externalId,
    externalNumber          = externalNumber,
    externalStatus          = externalStatus?.name,
    externalStatusLabel     = externalStatus?.displayName,
    isCorrection            = isCorrection,
    hasCorrection           = hasCorrection,
    correctionExternalId    = correctionExternalId,
    providerSyncStatus      = providerSyncStatus?.name,
    providerSyncStatusLabel = providerSyncStatus?.displayName,
    providerSyncError       = providerSyncError,
    syncedAt                = syncedAt,
    externalUrl             = externalUrl,
    ksefInvoiceId           = ksefInvoiceId?.toString(),
    ksefNumber              = ksefNumber,
    createdBy               = createdBy.toString(),
    createdAt               = createdAt,
    updatedAt               = updatedAt
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
