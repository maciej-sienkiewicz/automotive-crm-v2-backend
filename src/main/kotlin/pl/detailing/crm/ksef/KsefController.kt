package pl.detailing.crm.ksef

import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.auth.KsefSessionCache
import pl.detailing.crm.ksef.credentials.KsefCredentialsEntity
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.fetch.FetchExpensesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.ksef.statistics.KsefStatisticsHandler
import pl.detailing.crm.ksef.statistics.KsefStatisticsQuery
import pl.detailing.crm.ksef.sync.KsefSyncCursorRepository
import pl.detailing.crm.ksef.sync.KsefSyncService
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ksef")
class KsefController(
    private val credentialsRepository: KsefCredentialsRepository,
    private val sessionCache: KsefSessionCache,
    private val ksefAuthService: KsefAuthService,
    private val fetchHandler: FetchKsefInvoicesHandler,
    private val invoiceRepository: KsefInvoiceRepository,
    private val syncService: KsefSyncService,
    private val syncCursorRepository: KsefSyncCursorRepository,
    private val statisticsHandler: KsefStatisticsHandler
) {

    // ── Credentials ────────────────────────────────────────────────────────────

    @PostMapping("/credentials")
    @Transactional
    fun saveCredentials(@RequestBody req: SaveKsefCredentialsRequest): ResponseEntity<KsefCredentialsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value

        credentialsRepository.deleteByStudioId(studioId)
        sessionCache.invalidate(SecurityContextHelper.getCurrentUser().studioId)

        val saved = credentialsRepository.save(
            KsefCredentialsEntity(studioId = studioId, nip = req.nip.trim(), ksefToken = req.ksefToken.trim())
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    @GetMapping("/credentials")
    fun getCredentials(): ResponseEntity<KsefCredentialsResponse> {
        requireOwner()
        val entity = credentialsRepository.findByStudioId(SecurityContextHelper.getCurrentUser().studioId.value)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(entity.toResponse())
    }

    @DeleteMapping("/credentials")
    @Transactional
    fun deleteCredentials(): ResponseEntity<Void> {
        requireOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        credentialsRepository.deleteByStudioId(principal.studioId.value)
        sessionCache.invalidate(principal.studioId)
        return ResponseEntity.noContent().build()
    }

    // ── Sync ───────────────────────────────────────────────────────────────────

    @GetMapping("/sync/status")
    fun getSyncStatus(): ResponseEntity<KsefSyncStatusResponse> {
        requireManagerOrOwner()
        val cursor = syncCursorRepository.findById(SecurityContextHelper.getCurrentUser().studioId.value).orElse(null)
        return ResponseEntity.ok(
            KsefSyncStatusResponse(
                syncStatus      = cursor?.syncStatus ?: "NEVER_SYNCED",
                lastExpenseSync = cursor?.lastExpenseSync,
                lastError       = cursor?.lastError,
                updatedAt       = cursor?.updatedAt
            )
        )
    }

    @PostMapping("/sync/trigger")
    fun triggerSync(): ResponseEntity<KsefSyncStatusResponse> {
        requireManagerOrOwner()
        val studioId = SecurityContextHelper.getCurrentUser().studioId
        syncService.syncStudio(studioId)
        val cursor = syncCursorRepository.findById(studioId.value).orElse(null)
        return ResponseEntity.ok(
            KsefSyncStatusResponse(
                syncStatus      = cursor?.syncStatus ?: "NEVER_SYNCED",
                lastExpenseSync = cursor?.lastExpenseSync,
                lastError       = cursor?.lastError,
                updatedAt       = cursor?.updatedAt
            )
        )
    }

    // ── Expense documents (KSeF + manual) ─────────────────────────────────────

    /**
     * Paginated list of expense documents.
     * source: KSEF | MANUAL | null (all)
     * paymentStatus: PAID | PENDING | null (all)
     * includeExcluded: include hidden documents (default false)
     */
    @GetMapping("/expenses")
    fun listExpenses(
        @RequestParam(defaultValue = "1")    page: Int,
        @RequestParam(defaultValue = "20")   size: Int,
        @RequestParam(required = false)      source: String?,
        @RequestParam(required = false)      paymentStatus: String?,
        @RequestParam(required = false)      dateFrom: OffsetDateTime?,
        @RequestParam(required = false)      dateTo: OffsetDateTime?,
        @RequestParam(defaultValue = "false") includeExcluded: Boolean
    ): ResponseEntity<ExpenseListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val pageable  = PageRequest.of(maxOf(0, page - 1), size.coerceIn(1, 100))

        val result = invoiceRepository.findWithFilters(
            studioId        = principal.studioId.value,
            source          = source?.uppercase(),
            paymentStatus   = paymentStatus?.uppercase(),
            includeExcluded = includeExcluded,
            dateFrom        = dateFrom,
            dateTo          = dateTo,
            pageable        = pageable
        )

        return ResponseEntity.ok(
            ExpenseListResponse(
                expenses = result.content.map { it.toResponse() },
                total    = result.totalElements,
                page     = page,
                pageSize = result.size
            )
        )
    }

    /** Create a manual expense document (for invoices not received via KSeF). */
    @PostMapping("/expenses")
    @Transactional
    fun createManualExpense(@RequestBody req: CreateManualExpenseRequest): ResponseEntity<ExpenseResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()

        if ((req.grossAmount ?: 0.0) < 0 || (req.netAmount ?: 0.0) < 0) {
            throw ValidationException("Kwoty nie mogą być ujemne")
        }

        val paymentForm = req.paymentMethod?.let {
            runCatching { PaymentForm.valueOf(it.uppercase()) }.getOrElse {
                throw ValidationException("Nieznana forma płatności: $it. Dozwolone: ${PaymentForm.entries.joinToString { e -> e.name }}")
            }
        }

        val entity = KsefInvoiceEntity(
            studioId      = principal.studioId.value,
            source        = "MANUAL",
            ksefNumber    = "MANUAL-${UUID.randomUUID()}",
            invoiceNumber = req.documentNumber,
            invoicingDate = req.saleDate,
            issueDate     = req.saleDate?.toLocalDate(),
            sellerNip     = req.sellerNip,
            sellerName    = req.sellerName,
            buyerNip      = null,
            buyerName     = null,
            netAmount     = req.netAmount,
            grossAmount   = req.grossAmount,
            vatAmount     = if (req.grossAmount != null && req.netAmount != null) req.grossAmount - req.netAmount else null,
            currency      = "PLN",
            invoiceType   = null,
            direction     = "EXPENSE",
            status        = "ACTIVE",
            paymentStatus = "PENDING",
            paymentForm   = paymentForm?.name
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceRepository.save(entity).toResponse())
    }

    /** Fetch (pull) expense invoices from KSeF for a given date range. */
    @PostMapping("/expenses/sync")
    fun fetchFromKsef(@RequestBody req: FetchExpensesRequest): ResponseEntity<FetchExpensesResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()

        val result = fetchHandler.handle(
            FetchExpensesCommand(
                studioId = principal.studioId,
                dateFrom = req.dateFrom,
                dateTo   = req.dateTo
            )
        )
        return ResponseEntity.ok(FetchExpensesResponse(fetched = result.fetched, skipped = result.skipped))
    }

    /** Mark KSeF invoice as EXCLUDED (hidden from statistics and listings). */
    @PatchMapping("/expenses/{id}/exclude")
    @Transactional
    fun excludeExpense(@PathVariable id: UUID): ResponseEntity<Void> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = findExpenseOrThrow(id, principal.studioId.value)

        if (entity.status == "CANCELLED") {
            throw ValidationException("Nie można ukryć anulowanej faktury")
        }
        if (entity.status != "EXCLUDED") {
            invoiceRepository.updateStatus(principal.studioId.value, entity.ksefNumber, "EXCLUDED")
        }
        return ResponseEntity.noContent().build()
    }

    /** Restore a previously excluded expense document. */
    @PatchMapping("/expenses/{id}/restore")
    @Transactional
    fun restoreExpense(@PathVariable id: UUID): ResponseEntity<Void> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = findExpenseOrThrow(id, principal.studioId.value)

        if (entity.status == "EXCLUDED") {
            invoiceRepository.updateStatus(principal.studioId.value, entity.ksefNumber, "ACTIVE")
        }
        return ResponseEntity.noContent().build()
    }

    /** Update payment status (PAID / PENDING). */
    @PatchMapping("/expenses/{id}/payment-status")
    @Transactional
    fun updatePaymentStatus(
        @PathVariable id: UUID,
        @RequestBody req: UpdatePaymentStatusRequest
    ): ResponseEntity<ExpenseResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        findExpenseOrThrow(id, principal.studioId.value)

        val newStatus = req.paymentStatus.uppercase()
        if (newStatus != "PAID" && newStatus != "PENDING") {
            throw ValidationException("paymentStatus musi być PAID lub PENDING")
        }

        invoiceRepository.updatePaymentStatus(id, principal.studioId.value, newStatus)

        return ResponseEntity.ok(
            invoiceRepository.findByIdAndStudioId(id, principal.studioId.value)!!.toResponse()
        )
    }

    /** Add or edit the free-text note on an expense document. */
    @PatchMapping("/expenses/{id}/note")
    @Transactional
    fun upsertExpenseNote(
        @PathVariable id: UUID,
        @RequestBody req: UpsertNoteRequest
    ): ResponseEntity<ExpenseResponse> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        findExpenseOrThrow(id, principal.studioId.value)

        val note = req.note.trim()
        if (note.isEmpty()) {
            throw ValidationException("Notatka nie może być pusta")
        }

        invoiceRepository.updateNote(id, principal.studioId.value, note)

        return ResponseEntity.ok(
            invoiceRepository.findByIdAndStudioId(id, principal.studioId.value)!!.toResponse()
        )
    }

    /** Delete the note on an expense document. */
    @DeleteMapping("/expenses/{id}/note")
    @Transactional
    fun deleteExpenseNote(@PathVariable id: UUID): ResponseEntity<Void> {
        requireManagerOrOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        findExpenseOrThrow(id, principal.studioId.value)

        invoiceRepository.updateNote(id, principal.studioId.value, null)
        return ResponseEntity.noContent().build()
    }

    /** Delete a MANUAL expense document (KSeF invoices cannot be deleted — only excluded). */
    @DeleteMapping("/expenses/{id}")
    @Transactional
    fun deleteManualExpense(@PathVariable id: UUID): ResponseEntity<Void> {
        requireOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = findExpenseOrThrow(id, principal.studioId.value)

        if (entity.source != "MANUAL") {
            throw ValidationException("Można usuwać tylko ręcznie dodane dokumenty kosztowe. Faktury KSeF można wyłącznie ukryć.")
        }

        invoiceRepository.delete(entity)
        return ResponseEntity.noContent().build()
    }

    // ── Statistics ─────────────────────────────────────────────────────────────

    @GetMapping("/statistics")
    fun getStatistics(@RequestParam year: Int): ResponseEntity<KsefStatisticsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (year < 2000 || year > 2100) throw ValidationException("Rok musi być w zakresie 2000–2100")

        val result = statisticsHandler.handle(KsefStatisticsQuery(principal.studioId, year))
        val cursor  = syncCursorRepository.findById(principal.studioId.value).orElse(null)

        return ResponseEntity.ok(
            KsefStatisticsResponse(
                year       = result.year,
                totals     = KsefExpenseTotalsResponse(
                    costsGross      = result.totals.costsGross,
                    costsNet        = result.totals.costsNet,
                    costsVat        = result.totals.costsVat,
                    expenseCount    = result.totals.expenseCount,
                    correctionCount = result.totals.correctionCount
                ),
                monthly    = result.monthly.map { m ->
                    KsefMonthlyExpenseResponse(
                        month           = m.month,
                        costsGross      = m.costsGross,
                        costsNet        = m.costsNet,
                        costsVat        = m.costsVat,
                        expenseCount    = m.expenseCount,
                        correctionCount = m.correctionCount
                    )
                },
                dataAsOf   = cursor?.lastExpenseSync,
                syncStatus = cursor?.syncStatus ?: "NEVER_SYNCED"
            )
        )
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun requireOwner() {
        if (!SecurityContextHelper.getCurrentUser().isOwner) {
            throw ForbiddenException("Tylko właściciel może wykonać tę operację")
        }
    }

    private fun requireManagerOrOwner() {
        // MANAGER checks removed — access open to all authenticated users
    }

    private fun findExpenseOrThrow(id: UUID, studioId: UUID): KsefInvoiceEntity =
        invoiceRepository.findByIdAndStudioId(id, studioId)
            ?: throw NotFoundException("Dokument kosztowy $id nie istnieje")

    private fun KsefCredentialsEntity.toResponse() = KsefCredentialsResponse(
        nip         = nip,
        tokenMasked = maskToken(ksefToken),
        createdAt   = createdAt,
        updatedAt   = updatedAt
    )

    private fun maskToken(token: String): String =
        if (token.length <= 8) "****" else token.take(4) + "****" + token.takeLast(4)

    private fun KsefInvoiceEntity.toResponse() = ExpenseResponse(
        id             = id.toString(),
        source         = source,
        ksefNumber     = if (source == "KSEF") ksefNumber else null,
        documentNumber = invoiceNumber,
        saleDate       = invoicingDate,
        sellerName     = sellerName,
        sellerNip      = sellerNip,
        netAmount      = netAmount,
        grossAmount    = grossAmount,
        vatAmount      = vatAmount,
        currency       = currency ?: "PLN",
        paymentMethod  = paymentForm,
        paymentMethodLabel = paymentForm?.let { runCatching { PaymentForm.valueOf(it).displayName }.getOrNull() },
        paymentStatus  = paymentStatus,
        status         = status,
        isCorrection   = isCorrection,
        fetchedAt      = fetchedAt,
        note           = note
    )
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

data class SaveKsefCredentialsRequest(val nip: String, val ksefToken: String)

data class FetchExpensesRequest(val dateFrom: OffsetDateTime, val dateTo: OffsetDateTime)

data class CreateManualExpenseRequest(
    val saleDate: OffsetDateTime?,
    val documentNumber: String?,
    val sellerName: String?,
    val sellerNip: String?,
    val netAmount: Double?,
    val grossAmount: Double?,
    /** PaymentForm name: GOTOWKA | KARTA | PRZELEW | MOBILNA | KREDYT | BON | CZEK */
    val paymentMethod: String?
)

data class UpdatePaymentStatusRequest(val paymentStatus: String)

data class UpsertNoteRequest(val note: String)

// ── Response DTOs ──────────────────────────────────────────────────────────────

data class KsefCredentialsResponse(
    val nip: String,
    val tokenMasked: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class KsefSyncStatusResponse(
    val syncStatus: String,
    val lastExpenseSync: OffsetDateTime?,
    val lastError: String?,
    val updatedAt: OffsetDateTime?
)

data class FetchExpensesResponse(val fetched: Int, val skipped: Int)

data class ExpenseResponse(
    val id: String,
    val source: String,                 // KSEF | MANUAL
    val ksefNumber: String?,            // null for MANUAL
    val documentNumber: String?,
    val saleDate: OffsetDateTime?,
    val sellerName: String?,
    val sellerNip: String?,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String,
    val paymentMethod: String?,         // PaymentForm.name
    val paymentMethodLabel: String?,    // PaymentForm.displayName
    val paymentStatus: String,          // PAID | PENDING
    val status: String,                 // ACTIVE | CORRECTED | CANCELLED | EXCLUDED
    val isCorrection: Boolean,
    val fetchedAt: Instant,
    val note: String?
)

data class ExpenseListResponse(
    val expenses: List<ExpenseResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class KsefExpenseTotalsResponse(
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefMonthlyExpenseResponse(
    val month: String,
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefStatisticsResponse(
    val year: Int,
    val totals: KsefExpenseTotalsResponse,
    val monthly: List<KsefMonthlyExpenseResponse>,
    val dataAsOf: OffsetDateTime?,
    val syncStatus: String
)
