package pl.detailing.crm.ksef

import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.ksef.auth.KsefAuthService
import pl.detailing.crm.ksef.auth.KsefSessionCache
import pl.detailing.crm.ksef.auth.KsefTokenVerifier
import pl.detailing.crm.ksef.credentials.KsefCredentialsEntity
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemEntity
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceItemRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.ksef.statistics.KsefStatisticsHandler
import pl.detailing.crm.ksef.statistics.KsefStatisticsQuery
import pl.detailing.crm.ksef.sync.KsefSyncCursorRepository
import pl.detailing.crm.ksef.sync.KsefSyncService
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

@RequiresCapability(CapabilityKey.FINANCE_KSEF)
@RestController
@RequestMapping("/api/v1/ksef")
@RequiresPermission(Permission.FINANCE_INVOICES)
class KsefController(
    private val credentialsRepository: KsefCredentialsRepository,
    private val sessionCache: KsefSessionCache,
    private val ksefAuthService: KsefAuthService,
    private val tokenVerifier: KsefTokenVerifier,
    private val invoiceRepository: KsefInvoiceRepository,
    private val invoiceItemRepository: KsefInvoiceItemRepository,
    private val syncService: KsefSyncService,
    private val syncCursorRepository: KsefSyncCursorRepository,
    private val statisticsHandler: KsefStatisticsHandler,
    private val studioSettingsRepository: StudioSettingsRepository
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

    /**
     * Verifies the currently saved token against KSeF: authenticates with a fresh
     * session and reads which permissions the token actually carries, so the
     * settings view can show "token OK, but missing InvoiceWrite" instead of the
     * studio discovering it at their first invoice. The result is persisted on the
     * credentials row and also returned by GET /credentials.
     */
    @PostMapping("/credentials/verify")
    @Transactional
    fun verifyCredentials(): ResponseEntity<KsefTokenVerificationResponse> {
        requireOwner()
        val principal = SecurityContextHelper.getCurrentUser()
        val credentials = credentialsRepository.findByStudioId(principal.studioId.value)
            ?: return ResponseEntity.notFound().build()

        val result = tokenVerifier.verify(principal.studioId)

        credentials.lastVerifiedAt = Instant.now()
        credentials.verifiedTokenValid = result.tokenValid
        credentials.verifiedPermissions =
            if (result.permissionsKnown) result.permissions.joinToString(",") else null
        credentialsRepository.save(credentials)

        return ResponseEntity.ok(credentials.toVerificationResponse(result.errorMessage))
    }

    // ── Gotowość do fakturowania (odczyt dla ekranu wydania pojazdu) ───────────

    /**
     * Czy studio może dziś wystawić fakturę i wysłać ją do KSeF — jeden odczyt dla
     * ekranu wydania pojazdu.
     *
     * Osobno od `GET /credentials`, bo tamten endpoint jest właścicielski i zwraca
     * dane poświadczeń (NIP, maska tokenu). Wydania pojazdu dokonuje zwykły
     * pracownik, a on ma wiedzieć tylko tyle: czy faktura pojedzie sama, czy token
     * ma uprawnienie do wystawiania i jaka jest domyślna odpowiedź na pytanie
     * o wysyłkę. Żadna z tych wartości nie jest sekretem.
     */
    @GetMapping("/invoicing-status")
    @RequiresPermission(Permission.FINANCE_INVOICES, Permission.VISITS_VIEW)
    fun getInvoicingStatus(): ResponseEntity<KsefInvoicingStatusResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val credentials = credentialsRepository.findByStudioId(principal.studioId.value)
        val settings = studioSettingsRepository.findById(principal.studioId.value).orElse(null)

        val permissions = credentials?.verifiedPermissions?.split(",")?.filter { it.isNotBlank() }
        return ResponseEntity.ok(
            KsefInvoicingStatusResponse(
                configured       = credentials != null,
                tokenChecked     = credentials?.lastVerifiedAt != null,
                tokenValid       = credentials?.verifiedTokenValid == true,
                permissionsKnown = permissions != null,
                canIssueInvoices = permissions?.contains("InvoiceWrite") == true,
                checkedAt        = credentials?.lastVerifiedAt,
                autoSendDefault  = settings?.ksefAutoSendDefault ?: true
            )
        )
    }

    /**
     * Domyślna pozycja przełącznika „Wyślij fakturę do KSeF" przy wydaniu pojazdu.
     * Ustawienie studia, więc zmienia je właściciel; sama decyzja o konkretnej
     * fakturze pozostaje przy osobie wydającej pojazd.
     */
    @PatchMapping("/invoicing-settings")
    @Transactional
    fun updateInvoicingSettings(
        @RequestBody req: UpdateKsefInvoicingSettingsRequest
    ): ResponseEntity<KsefInvoicingSettingsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val settings = studioSettingsRepository.findById(studioId).orElse(null)
            ?: StudioSettingsEntity(studioId = studioId)

        settings.ksefAutoSendDefault = req.autoSendDefault
        settings.updatedAt = Instant.now()
        val saved = studioSettingsRepository.save(settings)

        return ResponseEntity.ok(KsefInvoicingSettingsResponse(autoSendDefault = saved.ksefAutoSendDefault))
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
        // LocalDate, not OffsetDateTime: the UI sends `2026-05-21`, which no
        // OffsetDateTime parser accepts — the filter answered 500 for every request that
        // used it. Matches the sibling endpoint /ksef/revenue/invoices, which already
        // took LocalDate and set the convention for this API.
        @RequestParam(required = false)      dateFrom: LocalDate?,
        @RequestParam(required = false)      dateTo: LocalDate?,
        @RequestParam(defaultValue = "false") includeExcluded: Boolean
    ): ResponseEntity<ExpenseListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val pageable  = PageRequest.of(maxOf(0, page - 1), size.coerceIn(1, 100))

        val result = invoiceRepository.findWithFilters(
            studioId        = principal.studioId.value,
            source          = source?.uppercase(),
            paymentStatus   = paymentStatus?.uppercase(),
            includeExcluded = includeExcluded,
            dateFrom        = DateRangeFilter.startOfDay(dateFrom),
            // dateTo names a day the user expects to see in full, so the bound is the
            // start of the next one; the query compares with `<`.
            dateToExclusive = DateRangeFilter.startOfNextDay(dateTo),
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

    /**
     * Full expense document detail — parties with addresses, payment info and line items.
     * Data source for the invoice visualization view.
     */
    @GetMapping("/expenses/{id}")
    fun getExpenseDetail(@PathVariable id: UUID): ResponseEntity<ExpenseDetailResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = findExpenseOrThrow(id, principal.studioId.value)
        val items = invoiceItemRepository.findByInvoiceIdOrderByLineNumberAsc(entity.id)
        return ResponseEntity.ok(entity.toDetailResponse(items))
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
            netAmount     = zlotyToGrosz(req.netAmount),
            grossAmount   = zlotyToGrosz(req.grossAmount),
            vatAmount     = if (req.grossAmount != null && req.netAmount != null) {
                zlotyToGrosz(req.grossAmount)!! - zlotyToGrosz(req.netAmount)!!
            } else null,
            currency      = "PLN",
            invoiceType   = null,
            direction     = "EXPENSE",
            status        = "ACTIVE",
            paymentStatus = "PENDING",
            paymentForm   = paymentForm?.name
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceRepository.save(entity).toResponse())
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

        invoiceItemRepository.deleteByInvoiceId(entity.id)
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

    /**
     * Baza trzyma kwoty kosztowe w groszach (V80), a kontrakt tego API jest
     * w złotych — konwersja siedzi wyłącznie tutaj, na granicy HTTP.
     * null zostaje nullem: brak kwoty to nie to samo co zero.
     */
    private fun groszToZloty(grosz: Long?): Double? = grosz?.let { it / 100.0 }

    private fun zlotyToGrosz(zloty: Double?): Long? =
        zloty?.let { BigDecimal.valueOf(it).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong() }

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
        nip          = nip,
        tokenMasked  = maskToken(ksefToken),
        createdAt    = createdAt,
        updatedAt    = updatedAt,
        verification = if (lastVerifiedAt == null) null else toVerificationResponse(errorMessage = null)
    )

    /**
     * Maps persisted verification state to the user-facing capability checklist.
     * There is no separate UPO permission in KSeF — UPO is available for sessions
     * the token opened itself, so InvoiceWrite implies it.
     */
    private fun KsefCredentialsEntity.toVerificationResponse(errorMessage: String?): KsefTokenVerificationResponse {
        val permissions = verifiedPermissions?.split(",")?.filter { it.isNotBlank() }
        val canIssue = permissions?.contains("InvoiceWrite") == true
        return KsefTokenVerificationResponse(
            tokenValid       = verifiedTokenValid == true,
            permissionsKnown = permissions != null,
            canIssueInvoices = canIssue,
            canReadInvoices  = permissions?.contains("InvoiceRead") == true,
            canGenerateUpo   = canIssue,
            permissions      = permissions.orEmpty(),
            checkedAt        = lastVerifiedAt,
            errorMessage     = errorMessage
        )
    }

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
        netAmount      = groszToZloty(netAmount),
        grossAmount    = groszToZloty(grossAmount),
        vatAmount      = groszToZloty(vatAmount),
        currency       = currency ?: "PLN",
        paymentMethod  = paymentForm,
        paymentMethodLabel = paymentForm?.let { runCatching { PaymentForm.valueOf(it).displayName }.getOrNull() },
        paymentStatus  = paymentStatus,
        status         = status,
        isCorrection   = isCorrection,
        fetchedAt      = fetchedAt,
        note           = note
    )

    private fun KsefInvoiceEntity.toDetailResponse(items: List<KsefInvoiceItemEntity>) = ExpenseDetailResponse(
        id             = id.toString(),
        source         = source,
        ksefNumber     = if (source == "KSEF") ksefNumber else null,
        documentNumber = invoiceNumber,
        saleDate       = invoicingDate,
        issueDate      = issueDate,
        invoiceType    = invoiceType,
        seller         = ExpensePartyResponse(
            name         = sellerName,
            nip          = sellerNip,
            addressLine1 = sellerAddressLine1,
            addressLine2 = sellerAddressLine2,
            countryCode  = sellerCountryCode
        ),
        buyer          = ExpensePartyResponse(
            name         = buyerName,
            nip          = buyerNip,
            addressLine1 = buyerAddressLine1,
            addressLine2 = buyerAddressLine2,
            countryCode  = buyerCountryCode
        ),
        netAmount      = groszToZloty(netAmount),
        grossAmount    = groszToZloty(grossAmount),
        vatAmount      = groszToZloty(vatAmount),
        currency       = currency ?: "PLN",
        payment        = ExpensePaymentResponse(
            method      = paymentForm,
            methodLabel = paymentForm?.let { runCatching { PaymentForm.valueOf(it).displayName }.getOrNull() },
            status      = paymentStatus,
            dueDate     = paymentDueDate,
            bankAccount = bankAccount
        ),
        items          = items.map { item ->
            ExpenseItemResponse(
                lineNumber   = item.lineNumber,
                name         = item.name,
                unit         = item.unit,
                quantity     = item.quantity?.toDouble(),
                unitPriceNet = groszToZloty(item.unitPriceNet),
                netValue     = groszToZloty(item.netValue),
                grossValue   = groszToZloty(item.grossValue),
                vatRate      = item.vatRate
            )
        },
        status         = status,
        isCorrection   = isCorrection,
        originalKsefNumber = originalKsefNumber,
        fetchedAt      = fetchedAt,
        note           = note
    )
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

data class SaveKsefCredentialsRequest(val nip: String, val ksefToken: String)

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
    val updatedAt: Instant,
    /** Last token-verification result, or null if the token was never verified. */
    val verification: KsefTokenVerificationResponse?
)

data class KsefTokenVerificationResponse(
    /** Did KSeF accept the token during authentication? */
    val tokenValid: Boolean,
    /** False = token authenticated but the permission list could not be read. */
    val permissionsKnown: Boolean,
    val canIssueInvoices: Boolean,
    val canReadInvoices: Boolean,
    /** UPO has no own permission in KSeF — it follows from InvoiceWrite. */
    val canGenerateUpo: Boolean,
    /** Raw KSeF permission names, e.g. ["InvoiceRead", "InvoiceWrite"]. */
    val permissions: List<String>,
    val checkedAt: Instant?,
    val errorMessage: String?
)

/**
 * Gotowość studia do wystawiania faktur w KSeF, w formie potrzebnej ekranowi
 * wydania pojazdu. [tokenChecked] = false oznacza „nie wiemy" (token nigdy nie był
 * weryfikowany) i nie wolno go czytać jako braku uprawnień.
 */
data class KsefInvoicingStatusResponse(
    val configured: Boolean,
    val tokenChecked: Boolean,
    val tokenValid: Boolean,
    val permissionsKnown: Boolean,
    val canIssueInvoices: Boolean,
    val checkedAt: Instant?,
    val autoSendDefault: Boolean
)

data class UpdateKsefInvoicingSettingsRequest(val autoSendDefault: Boolean)

data class KsefInvoicingSettingsResponse(val autoSendDefault: Boolean)

data class KsefSyncStatusResponse(
    val syncStatus: String,
    val lastExpenseSync: OffsetDateTime?,
    val lastError: String?,
    val updatedAt: OffsetDateTime?
)


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

/** Strona faktury (sprzedawca / nabywca) z danymi adresowymi. */
data class ExpensePartyResponse(
    val name: String?,
    val nip: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val countryCode: String?
)

/** Szczegóły płatności dokumentu kosztowego. */
data class ExpensePaymentResponse(
    val method: String?,        // PaymentForm.name
    val methodLabel: String?,   // PaymentForm.displayName
    val status: String,         // PAID | PENDING
    val dueDate: LocalDate?,
    val bankAccount: String?
)

/** Pozycja faktury (wiersz FaWiersz z KSeF). */
data class ExpenseItemResponse(
    val lineNumber: Int,
    val name: String?,
    val unit: String?,
    val quantity: Double?,
    val unitPriceNet: Double?,
    val netValue: Double?,
    val grossValue: Double?,
    val vatRate: String?
)

/** Pełne dane dokumentu kosztowego — podstawa do wizualizacji faktury. */
data class ExpenseDetailResponse(
    val id: String,
    val source: String,                 // KSEF | MANUAL
    val ksefNumber: String?,            // null for MANUAL
    val documentNumber: String?,
    val saleDate: OffsetDateTime?,
    val issueDate: LocalDate?,
    val invoiceType: String?,           // FA | FA_KOR | null (MANUAL)
    val seller: ExpensePartyResponse,
    val buyer: ExpensePartyResponse,
    val netAmount: Double?,
    val grossAmount: Double?,
    val vatAmount: Double?,
    val currency: String,
    val payment: ExpensePaymentResponse,
    val items: List<ExpenseItemResponse>,
    val status: String,                 // ACTIVE | CORRECTED | CANCELLED | EXCLUDED
    val isCorrection: Boolean,
    val originalKsefNumber: String?,
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
