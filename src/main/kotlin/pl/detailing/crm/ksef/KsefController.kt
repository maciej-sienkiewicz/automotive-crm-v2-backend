package pl.detailing.crm.ksef

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQueryDateType
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.auth.KsefSessionCache
import pl.detailing.crm.ksef.credentials.KsefCredentialsEntity
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
import pl.detailing.crm.ksef.list.ListKsefInvoicesCommand
import pl.detailing.crm.ksef.list.ListKsefInvoicesHandler
import pl.detailing.crm.ksef.statistics.KsefStatisticsHandler
import pl.detailing.crm.ksef.statistics.KsefStatisticsQuery
import pl.detailing.crm.ksef.sync.KsefSyncCursorRepository
import pl.detailing.crm.ksef.sync.KsefSyncService
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/v1/ksef")
class KsefController(
    private val credentialsRepository: KsefCredentialsRepository,
    private val sessionCache: KsefSessionCache,
    private val ksefAuthService: KsefAuthService,
    private val fetchInvoicesHandler: FetchKsefInvoicesHandler,
    private val listInvoicesHandler: ListKsefInvoicesHandler,
    private val statisticsHandler: KsefStatisticsHandler,
    private val syncService: KsefSyncService,
    private val syncCursorRepository: KsefSyncCursorRepository
) {

    // ─────────────────────────────────────────────────────────────────────
    // Credentials management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Zapisz (utwórz lub zastąp) dane dostępowe KSeF dla bieżącego studia.
     * POST /api/v1/ksef/credentials
     */
    @PostMapping("/credentials")
    @Transactional
    fun saveCredentials(@RequestBody request: SaveKsefCredentialsRequest): ResponseEntity<KsefCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can configure KSeF credentials")
        }

        val studioId = principal.studioId.value

        credentialsRepository.deleteByStudioId(studioId)
        sessionCache.invalidate(principal.studioId)

        val entity = credentialsRepository.save(
            KsefCredentialsEntity(
                studioId = studioId,
                nip = request.nip.trim(),
                ksefToken = request.ksefToken.trim()
            )
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(entity.toResponse())
    }

    /**
     * Pobierz dane dostępowe KSeF (token zamaskowany).
     * GET /api/v1/ksef/credentials
     */
    @GetMapping("/credentials")
    fun getCredentials(): ResponseEntity<KsefCredentialsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can view KSeF credentials")
        }

        val entity = credentialsRepository.findByStudioId(principal.studioId.value)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(entity.toResponse())
    }

    /**
     * Usuń dane dostępowe KSeF.
     * DELETE /api/v1/ksef/credentials
     */
    @DeleteMapping("/credentials")
    @Transactional
    fun deleteCredentials(): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can delete KSeF credentials")
        }

        credentialsRepository.deleteByStudioId(principal.studioId.value)
        sessionCache.invalidate(principal.studioId)

        return ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Otwórz sesję KSeF (weryfikacja credentials lub pre-warm cache).
     * POST /api/v1/ksef/session
     */
    @PostMapping("/session")
    fun startSession(): ResponseEntity<KsefSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER or MANAGER can manage KSeF sessions")
        }

        val session = ksefAuthService.authenticate(principal.studioId)

        return ResponseEntity.ok(
            KsefSessionResponse(
                authenticated = true,
                accessTokenValidUntil = session.accessTokenValidUntil
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invoice fetching (manual)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ręczny fetch faktur z KSeF dla podanego zakresu dat.
     * POST /api/v1/ksef/invoices/fetch
     */
    @PostMapping("/invoices/fetch")
    fun fetchInvoices(@RequestBody request: FetchKsefInvoicesRequest): ResponseEntity<FetchKsefInvoicesResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER or MANAGER can fetch KSeF invoices")
        }

        val dateType = when (request.dateType?.uppercase()) {
            "ISSUE", "ISSUEDATE"                                 -> InvoiceQueryDateType.ISSUE
            "PERMANENTSTORAGE", "PERMANENTSTORAGEDATE"           -> InvoiceQueryDateType.PERMANENTSTORAGE
            else                                                 -> InvoiceQueryDateType.INVOICING
        }

        val subjectType = when (request.subjectType?.uppercase()) {
            "SUBJECT2"          -> InvoiceQuerySubjectType.SUBJECT2
            "SUBJECT3"          -> InvoiceQuerySubjectType.SUBJECT3
            "SUBJECTAUTHORIZED" -> InvoiceQuerySubjectType.SUBJECTAUTHORIZED
            else                -> InvoiceQuerySubjectType.SUBJECT1
        }

        val result = fetchInvoicesHandler.handle(
            FetchKsefInvoicesCommand(
                studioId    = principal.studioId,
                dateFrom    = request.dateFrom,
                dateTo      = request.dateTo,
                dateType    = dateType,
                subjectType = subjectType,
                pageSize    = request.pageSize ?: 50
            )
        )

        return ResponseEntity.ok(
            FetchKsefInvoicesResponse(
                fetched = result.fetched,
                skipped = result.skipped,
                total   = result.fetched + result.skipped
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invoice listing
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Lista lokalnie zapisanych faktur KSeF.
     * GET /api/v1/ksef/invoices?page=1&size=20&direction=INCOME
     *
     * direction (opcjonalne): INCOME | EXPENSE – filtruje po kierunku faktury
     */
    @GetMapping("/invoices")
    fun listInvoices(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<KsefInvoiceListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listInvoicesHandler.handle(
            ListKsefInvoicesCommand(
                studioId = principal.studioId,
                page     = maxOf(1, page),
                pageSize = size.coerceIn(1, 100)
            )
        )

        return ResponseEntity.ok(
            KsefInvoiceListResponse(
                invoices = result.invoices.map { inv ->
                    KsefInvoiceResponse(
                        id               = inv.id.toString(),
                        ksefNumber       = inv.ksefNumber,
                        invoiceNumber    = inv.invoiceNumber,
                        invoicingDate    = inv.invoicingDate,
                        issueDate        = inv.issueDate?.toString(),
                        sellerNip        = inv.sellerNip,
                        buyerNip         = inv.buyerNip,
                        netAmount        = inv.netAmount,
                        grossAmount      = inv.grossAmount,
                        vatAmount        = inv.vatAmount,
                        currency         = inv.currency,
                        invoiceType      = inv.invoiceType,
                        fetchedAt        = inv.fetchedAt,
                        direction        = inv.direction,
                        isCorrection     = inv.isCorrection,
                        status           = inv.status,
                        paymentForm      = inv.paymentForm?.name,
                        paymentFormLabel = inv.paymentForm?.displayName
                    )
                },
                total    = result.total,
                page     = result.page,
                pageSize = result.pageSize
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Synchronizacja (background sync management)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Status synchronizacji KSeF dla bieżącego studia.
     * GET /api/v1/ksef/sync/status
     */
    @GetMapping("/sync/status")
    fun getSyncStatus(): ResponseEntity<KsefSyncStatusResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER or MANAGER can view KSeF sync status")
        }

        val cursor = syncCursorRepository.findById(principal.studioId.value).orElse(null)

        return ResponseEntity.ok(
            KsefSyncStatusResponse(
                syncStatus      = cursor?.syncStatus ?: "NEVER_SYNCED",
                lastIncomeSync  = cursor?.lastIncomeSync,
                lastExpenseSync = cursor?.lastExpenseSync,
                lastError       = cursor?.lastError,
                updatedAt       = cursor?.updatedAt
            )
        )
    }

    /**
     * Wyzwolenie ręcznej synchronizacji dla bieżącego studia.
     * POST /api/v1/ksef/sync/trigger
     *
     * Uruchamia sync asynchronicznie w osobnym wątku – odpowiedź wraca natychmiast.
     * Status sync możesz śledzić przez GET /api/v1/ksef/sync/status.
     */
    @PostMapping("/sync/trigger")
    fun triggerSync(): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER or MANAGER can trigger KSeF sync")
        }

        val studioId = principal.studioId

        // Uruchomienie w osobnym wątku – nie blokuje requesta
        Thread.ofVirtual().name("ksef-manual-sync-${studioId.value}").start {
            syncService.syncStudio(studioId)
        }

        return ResponseEntity.accepted().build()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Statystyki finansowe
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Statystyki przychodów i kosztów z KSeF z podziałem miesięcznym.
     * GET /api/v1/ksef/statistics?year=2024
     *
     * Korekty (FA_KOR) wliczane automatycznie – ich kwoty mają odpowiedni znak,
     * więc SUM() daje prawidłowy wynik netto bez dodatkowego filtrowania.
     */
    @GetMapping("/statistics")
    fun getStatistics(
        @RequestParam year: Int
    ): ResponseEntity<KsefStatisticsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (year < 2000 || year > 2100) {
            throw ValidationException("Year must be between 2000 and 2100")
        }

        val result = statisticsHandler.handle(
            KsefStatisticsQuery(studioId = principal.studioId, year = year)
        )

        val cursor = syncCursorRepository.findById(principal.studioId.value).orElse(null)

        return ResponseEntity.ok(
            KsefStatisticsResponse(
                year = result.year,
                totals = KsefStatisticsTotalsResponse(
                    revenueGross    = result.totals.revenueGross,
                    revenueNet      = result.totals.revenueNet,
                    revenueVat      = result.totals.revenueVat,
                    costsGross      = result.totals.costsGross,
                    costsNet        = result.totals.costsNet,
                    costsVat        = result.totals.costsVat,
                    profitGross     = result.totals.profitGross,
                    profitNet       = result.totals.profitNet,
                    incomeCount     = result.totals.incomeCount,
                    expenseCount    = result.totals.expenseCount,
                    correctionCount = result.totals.correctionCount
                ),
                monthly = result.monthly.map { m ->
                    KsefMonthlyBreakdownResponse(
                        monthLabel      = m.monthLabel,
                        revenueGross    = m.revenueGross,
                        revenueNet      = m.revenueNet,
                        revenueVat      = m.revenueVat,
                        costsGross      = m.costsGross,
                        costsNet        = m.costsNet,
                        costsVat        = m.costsVat,
                        profitGross     = m.profitGross,
                        profitNet       = m.profitNet,
                        incomeCount     = m.incomeCount,
                        expenseCount    = m.expenseCount,
                        correctionCount = m.correctionCount
                    )
                },
                dataAsOf = cursor?.lastIncomeSync ?: cursor?.updatedAt,
                syncStatus = cursor?.syncStatus ?: "NEVER_SYNCED"
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun KsefCredentialsEntity.toResponse() = KsefCredentialsResponse(
        nip          = nip,
        tokenMasked  = maskToken(ksefToken),
        createdAt    = createdAt,
        updatedAt    = updatedAt
    )

    private fun maskToken(token: String): String =
        if (token.length <= 8) "****" else token.take(4) + "****" + token.takeLast(4)
}

// ─────────────────────────────────────────────────────────────────────────────
// Request / Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class SaveKsefCredentialsRequest(
    val nip: String,
    val ksefToken: String
)

data class KsefCredentialsResponse(
    val nip: String,
    val tokenMasked: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class KsefSessionResponse(
    val authenticated: Boolean,
    val accessTokenValidUntil: OffsetDateTime
)

data class FetchKsefInvoicesRequest(
    val dateFrom: OffsetDateTime,
    val dateTo: OffsetDateTime,
    val dateType: String?,    // INVOICING (default) | ISSUE | PERMANENTSTORAGE
    val subjectType: String?, // SUBJECT1 sprzedawca/przychód (default) | SUBJECT2 nabywca/koszt
    val pageSize: Int?
)

data class FetchKsefInvoicesResponse(
    val fetched: Int,
    val skipped: Int,
    val total: Int
)

data class KsefInvoiceResponse(
    val id: String,
    val ksefNumber: String,
    val invoiceNumber: String?,
    val invoicingDate: OffsetDateTime?,
    val issueDate: String?,
    val sellerNip: String?,
    val buyerNip: String?,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String?,
    val invoiceType: String?,
    val fetchedAt: Instant,
    val direction: String,           // INCOME | EXPENSE
    val isCorrection: Boolean,       // true = FA_KOR
    val status: String,              // ACTIVE | CORRECTED | CANCELLED
    val paymentForm: String?,        // np. "PRZELEW", "GOTOWKA" – null gdy niedostępna
    val paymentFormLabel: String?    // czytelna etykieta np. "Przelew", "Gotówka"
)

data class KsefInvoiceListResponse(
    val invoices: List<KsefInvoiceResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class KsefSyncStatusResponse(
    val syncStatus: String,             // IDLE | RUNNING | ERROR | NEVER_SYNCED
    val lastIncomeSync: OffsetDateTime?,
    val lastExpenseSync: OffsetDateTime?,
    val lastError: String?,
    val updatedAt: OffsetDateTime?
)

data class KsefStatisticsResponse(
    val year: Int,
    val totals: KsefStatisticsTotalsResponse,
    val monthly: List<KsefMonthlyBreakdownResponse>,
    val dataAsOf: OffsetDateTime?,      // data ostatniej synchronizacji
    val syncStatus: String
)

data class KsefStatisticsTotalsResponse(
    val revenueGross: Double,
    val revenueNet: Double,
    val revenueVat: Double,
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val profitGross: Double,
    val profitNet: Double,
    val incomeCount: Long,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefMonthlyBreakdownResponse(
    val monthLabel: String,
    val revenueGross: Double,
    val revenueNet: Double,
    val revenueVat: Double,
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val profitGross: Double,
    val profitNet: Double,
    val incomeCount: Long,
    val expenseCount: Long,
    val correctionCount: Long
)
