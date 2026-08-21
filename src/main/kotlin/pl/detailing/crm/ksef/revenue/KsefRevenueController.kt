package pl.detailing.crm.ksef.revenue

import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.ksef.qr.KsefQrCodeUrlBuilder
import pl.detailing.crm.ksef.revenue.domain.DuplicateStatus
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceItemRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.ksef.revenue.issue.IssueCorrectionCommand
import pl.detailing.crm.ksef.revenue.issue.CorrectionItemCommand
import pl.detailing.crm.ksef.revenue.issue.IssueCorrectionHandler
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceCommand
import pl.detailing.crm.ksef.revenue.issue.IssueRevenueInvoiceHandler
import pl.detailing.crm.ksef.revenue.issue.RevenueInvoiceBuyerCommand
import pl.detailing.crm.ksef.revenue.issue.RevenueInvoiceItemCommand
import pl.detailing.crm.ksef.revenue.send.KsefRevenueDispatchService
import pl.detailing.crm.ksef.revenue.statistics.RevenueStatisticsHandler
import pl.detailing.crm.ksef.revenue.statistics.RevenueStatisticsQuery
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

/**
 * API modułu faktur przychodowych KSeF.
 *
 * Ledger łączy faktury wystawione w CRM (source=CRM, pełny cykl wysyłki do KSeF)
 * z fakturami sprzedażowymi pobranymi z KSeF, a wystawionymi poza CRM
 * (source=EXTERNAL). Wystawienie = utworzenie + automatyczna wysyłka do KSeF.
 */
@RequiresCapability(CapabilityKey.FINANCE_KSEF)
@RestController
@RequestMapping("/api/v1/ksef/revenue")
@RequiresPermission(Permission.FINANCE_INVOICES)
class KsefRevenueController(
    private val invoiceRepository: KsefRevenueInvoiceRepository,
    private val itemRepository: KsefRevenueInvoiceItemRepository,
    private val issueHandler: IssueRevenueInvoiceHandler,
    private val correctionHandler: IssueCorrectionHandler,
    private val dispatchService: KsefRevenueDispatchService,
    private val statisticsHandler: RevenueStatisticsHandler,
    private val qrCodeUrlBuilder: KsefQrCodeUrlBuilder
) {

    // ── Wystawianie ────────────────────────────────────────────────────────────

    /** Wystawia fakturę przychodową i wysyła ją do KSeF. */
    @PostMapping("/invoices")
    fun issueInvoice(@RequestBody req: IssueInvoiceRequest): ResponseEntity<RevenueInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val invoice = issueHandler.handle(
            IssueRevenueInvoiceCommand(
                studioId = principal.studioId,
                userId   = principal.userId,
                buyer = RevenueInvoiceBuyerCommand(
                    nip          = req.buyer.nip,
                    name         = req.buyer.name,
                    addressLine1 = req.buyer.addressLine1,
                    addressLine2 = req.buyer.addressLine2,
                    countryCode  = req.buyer.countryCode ?: "PL",
                    email        = req.buyer.email
                ),
                items = req.items.map {
                    RevenueInvoiceItemCommand(
                        name           = it.name,
                        unit           = it.unit ?: "szt.",
                        quantity       = it.quantity ?: BigDecimal.ONE,
                        unitPriceNet   = it.unitPriceNet,
                        unitPriceGross = it.unitPriceGross,
                        vatRate        = it.vatRate
                    )
                },
                issueDate           = req.issueDate ?: LocalDate.now(),
                saleDate            = req.saleDate,
                paymentForm         = req.paymentForm?.uppercase(),
                isPaid              = req.isPaid ?: false,
                paymentDueDate      = req.paymentDueDate,
                exemptionLegalBasis = req.exemptionLegalBasis,
                visitId             = req.visitId,
                customerId          = req.customerId,
                description         = req.description
            )
        )
        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(invoice.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(invoice.toResponse(items))
    }

    /** Wystawia fakturę korygującą (FA_KOR). Bez pozycji → korekta pełna do zera. */
    @PostMapping("/invoices/{id}/corrections")
    fun issueCorrection(
        @PathVariable id: UUID,
        @RequestBody req: IssueCorrectionRequest
    ): ResponseEntity<RevenueInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val correction = correctionHandler.handle(
            IssueCorrectionCommand(
                studioId          = principal.studioId,
                userId            = principal.userId,
                originalInvoiceId = id,
                reason            = req.reason,
                items = req.items?.map {
                    CorrectionItemCommand(
                        name         = it.name,
                        unit         = it.unit ?: "szt.",
                        quantity     = it.quantity ?: BigDecimal.ONE,
                        unitPriceNet = it.unitPriceNet
                            ?: throw ValidationException("Pozycje korekty różnicowej podaje się w kwotach netto"),
                        vatRate      = it.vatRate
                    )
                },
                issueDate           = req.issueDate ?: LocalDate.now(),
                exemptionLegalBasis = req.exemptionLegalBasis
            )
        )
        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(correction.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(correction.toResponse(items))
    }

    /**
     * Ponawia wysyłkę faktury z kolejki retry (offline24) lub dokańcza polling
     * faktury przyjętej do sesji. Idempotentne — faktura ACCEPTED nie zostanie
     * wysłana drugi raz.
     */
    @PostMapping("/invoices/{id}/retry")
    fun retryInvoice(@PathVariable id: UUID): ResponseEntity<RevenueInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)

        if (invoice.source != RevenueSource.CRM) {
            throw ValidationException("Ponawiać można tylko faktury wystawione w CRM")
        }
        val updated = when (invoice.ksefStatus) {
            // NOT_SENT — faktura wystawiona świadomie bez wysyłki; ten endpoint jest
            // dla niej normalną drogą „wyślij teraz", nie ponowieniem po błędzie.
            KsefRevenueStatus.NOT_SENT,
            KsefRevenueStatus.PENDING,
            KsefRevenueStatus.QUEUED_RETRY -> dispatchService.dispatch(invoice)

            KsefRevenueStatus.SUBMITTED,
            KsefRevenueStatus.ACCEPTED -> dispatchService.complete(invoice)

            KsefRevenueStatus.REJECTED -> throw ValidationException(
                "Faktura została odrzucona przez walidację KSeF — popraw dane (np. dane firmy " +
                    "w ustawieniach) i wystaw nową fakturę. Odrzuconą fakturę można usunąć."
            )

            KsefRevenueStatus.SENDING -> throw ValidationException("Wysyłka faktury właśnie trwa")
        }
        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(updated.id)
        return ResponseEntity.ok(updated.toResponse(items))
    }

    /** Usuwa fakturę odrzuconą przez KSeF (jedyny usuwalny stan — dokument nie istnieje w KSeF). */
    @DeleteMapping("/invoices/{id}")
    @Transactional
    fun deleteRejected(@PathVariable id: UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)

        if (invoice.source != RevenueSource.CRM || invoice.ksefStatus != KsefRevenueStatus.REJECTED) {
            throw ValidationException(
                "Usunąć można wyłącznie fakturę odrzuconą przez KSeF. Faktury przyjęte " +
                    "w KSeF koryguje się fakturą korygującą."
            )
        }
        itemRepository.deleteByInvoiceId(invoice.id)
        invoiceRepository.delete(invoice)
        return ResponseEntity.noContent().build()
    }

    // ── Listowanie / szczegóły ─────────────────────────────────────────────────

    @GetMapping("/invoices")
    fun listInvoices(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) ksefStatus: String?,
        @RequestParam(required = false) duplicateStatus: String?,
        @RequestParam(required = false) dateFrom: LocalDate?,
        @RequestParam(required = false) dateTo: LocalDate?,
        @RequestParam(defaultValue = "false") includeExcluded: Boolean
    ): ResponseEntity<RevenueInvoiceListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val pageable = PageRequest.of(maxOf(0, page - 1), size.coerceIn(1, 100))

        val result = invoiceRepository.findWithFilters(
            studioId        = principal.studioId.value,
            source          = source?.uppercase(),
            ksefStatus      = ksefStatus?.uppercase(),
            duplicateStatus = duplicateStatus?.uppercase(),
            dateFrom        = dateFrom,
            dateTo          = dateTo,
            includeExcluded = includeExcluded,
            pageable        = pageable
        )

        return ResponseEntity.ok(
            RevenueInvoiceListResponse(
                invoices = result.content.map { it.toResponse(items = null) },
                total    = result.totalElements,
                page     = page,
                pageSize = result.size
            )
        )
    }

    @GetMapping("/invoices/{id}")
    fun getInvoice(@PathVariable id: UUID): ResponseEntity<RevenueInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)
        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(invoice.id)
        return ResponseEntity.ok(invoice.toResponse(items))
    }

    /** XML faktury (FA(3)) — dokładnie ten wysłany do KSeF. */
    @GetMapping("/invoices/{id}/xml")
    fun downloadXml(@PathVariable id: UUID): ResponseEntity<String> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)
        val xml = invoice.invoiceXml
            ?: throw NotFoundException("Faktura nie ma zapisanego XML (faktura zewnętrzna)")
        return xmlDownload(xml, "faktura-${invoice.invoiceNumber.replace('/', '-')}.xml")
    }

    /** UPO (Urzędowe Poświadczenie Odbioru) faktury przyjętej w KSeF. */
    @GetMapping("/invoices/{id}/upo")
    fun downloadUpo(@PathVariable id: UUID): ResponseEntity<String> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)
        val upo = invoice.upoXml
            ?: throw NotFoundException(
                "UPO nie jest jeszcze dostępne — zostanie pobrane automatycznie po przetworzeniu faktury"
            )
        return xmlDownload(upo, "upo-${invoice.invoiceNumber.replace('/', '-')}.xml")
    }

    // ── Duplikaty / status płatności / notatka ────────────────────────────────

    /**
     * Rozstrzyga podejrzenie podwójnego fakturowania.
     * - CONFIRMED_DUPLICATE: faktura {id} to duplikat — wypada ze statystyk
     *   (nadmiarowy dokument należy skorygować do zera w systemie, w którym powstał);
     *   sparowana faktura wraca do stanu rozstrzygniętego (DISMISSED).
     * - DISMISSED: to odrębne transakcje — obie strony pary rozstrzygnięte.
     */
    @PatchMapping("/invoices/{id}/duplicate-resolution")
    @Transactional
    fun resolveDuplicate(
        @PathVariable id: UUID,
        @RequestBody req: DuplicateResolutionRequest
    ): ResponseEntity<RevenueInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)

        if (invoice.duplicateStatus != DuplicateStatus.SUSPECTED) {
            throw ValidationException("Faktura nie ma nierozstrzygniętego podejrzenia duplikatu")
        }
        val resolution = runCatching { DuplicateStatus.valueOf(req.resolution.uppercase()) }
            .getOrElse { throw ValidationException("resolution musi być CONFIRMED_DUPLICATE lub DISMISSED") }
        if (resolution != DuplicateStatus.CONFIRMED_DUPLICATE && resolution != DuplicateStatus.DISMISSED) {
            throw ValidationException("resolution musi być CONFIRMED_DUPLICATE lub DISMISSED")
        }

        invoice.duplicateStatus = resolution
        invoice.updatedAt = Instant.now()
        invoiceRepository.save(invoice)

        invoice.duplicateOfId
            ?.let { invoiceRepository.findByIdAndStudioId(it, principal.studioId.value) }
            ?.takeIf { it.duplicateStatus == DuplicateStatus.SUSPECTED }
            ?.let { paired ->
                paired.duplicateStatus = DuplicateStatus.DISMISSED
                paired.updatedAt = Instant.now()
                invoiceRepository.save(paired)
            }

        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(invoice.id)
        return ResponseEntity.ok(invoice.toResponse(items))
    }

    @PatchMapping("/invoices/{id}/payment-status")
    @Transactional
    fun updatePaymentStatus(
        @PathVariable id: UUID,
        @RequestBody req: UpdateRevenuePaymentStatusRequest
    ): ResponseEntity<RevenueInvoiceResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)

        val newStatus = req.paymentStatus.uppercase()
        if (newStatus != "PAID" && newStatus != "PENDING") {
            throw ValidationException("paymentStatus musi być PAID lub PENDING")
        }
        invoice.paymentStatus = newStatus
        invoice.paidAt = if (newStatus == "PAID") (invoice.paidAt ?: Instant.now()) else null
        invoice.updatedAt = Instant.now()
        invoiceRepository.save(invoice)

        val items = itemRepository.findByInvoiceIdOrderByLineNumberAsc(invoice.id)
        return ResponseEntity.ok(invoice.toResponse(items))
    }

    @PatchMapping("/invoices/{id}/note")
    @Transactional
    fun upsertNote(@PathVariable id: UUID, @RequestBody req: RevenueNoteRequest): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val invoice = findOrThrow(id, principal.studioId.value)
        invoice.note = req.note?.trim()?.ifBlank { null }
        invoice.updatedAt = Instant.now()
        invoiceRepository.save(invoice)
        return ResponseEntity.noContent().build()
    }

    // ── Statystyki ─────────────────────────────────────────────────────────────

    @GetMapping("/statistics")
    fun getStatistics(@RequestParam year: Int): ResponseEntity<RevenueStatisticsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (year < 2000 || year > 2100) throw ValidationException("Rok musi być w zakresie 2000–2100")

        val result = statisticsHandler.handle(RevenueStatisticsQuery(principal.studioId, year))
        val pendingCount = invoiceRepository.countByStudioIdAndKsefStatusIn(
            principal.studioId.value,
            listOf(KsefRevenueStatus.QUEUED_RETRY, KsefRevenueStatus.SUBMITTED)
        )

        return ResponseEntity.ok(
            RevenueStatisticsResponse(
                year = result.year,
                totals = RevenueTotalsResponse(
                    gross           = result.totals.gross,
                    net             = result.totals.net,
                    vat             = result.totals.vat,
                    invoiceCount    = result.totals.invoiceCount,
                    correctionCount = result.totals.correctionCount,
                    externalCount   = result.totals.externalCount
                ),
                monthly = result.monthly.map {
                    MonthlyRevenueResponse(
                        month           = it.month,
                        gross           = it.gross,
                        net             = it.net,
                        vat             = it.vat,
                        invoiceCount    = it.invoiceCount,
                        correctionCount = it.correctionCount,
                        externalCount   = it.externalCount
                    )
                },
                pendingKsefCount = pendingCount.toLong()
            )
        )
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun findOrThrow(id: UUID, studioId: UUID): KsefRevenueInvoiceEntity =
        invoiceRepository.findByIdAndStudioId(id, studioId)
            ?: throw NotFoundException("Faktura przychodowa $id nie istnieje")

    private fun xmlDownload(content: String, filename: String): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(content)

    private fun KsefRevenueInvoiceEntity.toResponse(items: List<KsefRevenueInvoiceItemEntity>?) =
        RevenueInvoiceResponse(
            id                 = id.toString(),
            source             = source.name,
            ksefStatus         = ksefStatus.name,
            invoiceNumber      = invoiceNumber,
            ksefNumber         = ksefNumber,
            invoiceType        = invoiceType.name,
            originalInvoiceId  = originalInvoiceId?.toString(),
            originalKsefNumber = originalKsefNumber,
            correctionReason   = correctionReason,
            issueDate          = issueDate,
            saleDate           = saleDate,
            seller = RevenuePartyResponse(
                nip = sellerNip, name = sellerName,
                addressLine1 = sellerAddressLine1, addressLine2 = sellerAddressLine2,
                countryCode = sellerCountryCode, bankAccount = sellerBankAccount
            ),
            buyer = RevenuePartyResponse(
                nip = buyerNip, name = buyerName,
                addressLine1 = buyerAddressLine1, addressLine2 = buyerAddressLine2,
                countryCode = buyerCountryCode
            ),
            totalNet           = totalNet,
            totalVat           = totalVat,
            totalGross         = totalGross,
            currency           = currency,
            paymentForm        = paymentForm,
            paymentFormLabel   = paymentForm?.let { runCatching { PaymentForm.valueOf(it).displayName }.getOrNull() },
            paymentStatus      = paymentStatus,
            paymentDueDate     = paymentDueDate,
            duplicateStatus    = duplicateStatus.name,
            duplicateOfId      = duplicateOfId?.toString(),
            hasUpo             = upoXml != null,
            hasXml             = invoiceXml != null,
            sendAttempts       = sendAttempts,
            lastSendError      = lastSendError,
            sentAt             = sentAt,
            acceptedAt         = acceptedAt,
            visitId            = visitId?.toString(),
            customerId         = customerId?.toString(),
            description        = description,
            note               = note,
            excluded           = isExcluded,
            excludedAt         = excludedAt,
            detailsSynced      = hasCompleteDetails,
            ksefVerificationUrl = qrCodeUrlBuilder.buildInvoiceVerificationUrl(
                sellerNip   = sellerNip,
                issueDate   = issueDate,
                invoiceHash = invoiceHash ?: invoiceXml?.let { qrCodeUrlBuilder.hashInvoiceXml(it) }
            ),
            createdAt          = createdAt,
            items = items?.map {
                RevenueItemResponse(
                    lineNumber   = it.lineNumber,
                    name         = it.name,
                    unit         = it.unit,
                    quantity     = it.quantity,
                    unitPriceNet = it.unitPriceNet,
                    netValue     = it.netValue,
                    vatValue     = it.vatValue,
                    grossValue   = it.grossValue,
                    vatRate      = it.vatRate
                )
            }
        )
}

// ── Request DTOs ───────────────────────────────────────────────────────────────

data class IssueInvoiceBuyerRequest(
    val nip: String? = null,
    val name: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val countryCode: String? = null,
    val email: String? = null
)

data class IssueInvoiceItemRequest(
    val name: String,
    val unit: String? = null,
    val quantity: BigDecimal? = null,
    /** Cena jednostkowa netto w groszach — dokładnie jedno z pól netto/brutto. */
    val unitPriceNet: Long? = null,
    /** Cena jednostkowa brutto w groszach (VAT „w stu", brutto dokładnie jak wpisane). */
    val unitPriceGross: Long? = null,
    /** 23 | 8 | 5 | 0 | zw */
    val vatRate: String
)

data class IssueInvoiceRequest(
    val buyer: IssueInvoiceBuyerRequest,
    val items: List<IssueInvoiceItemRequest>,
    val issueDate: LocalDate? = null,
    val saleDate: LocalDate? = null,
    /** PaymentForm.name: GOTOWKA | KARTA | PRZELEW | MOBILNA | KREDYT | BON | CZEK */
    val paymentForm: String? = null,
    val isPaid: Boolean? = null,
    val paymentDueDate: LocalDate? = null,
    val exemptionLegalBasis: String? = null,
    val visitId: UUID? = null,
    val customerId: UUID? = null,
    val description: String? = null
)

data class IssueCorrectionRequest(
    val reason: String,
    /** null → korekta pełna do zera; podane → pozycje różnicowe (kwoty mogą być ujemne). */
    val items: List<IssueInvoiceItemRequest>? = null,
    val issueDate: LocalDate? = null,
    val exemptionLegalBasis: String? = null
)

data class DuplicateResolutionRequest(val resolution: String)

data class UpdateRevenuePaymentStatusRequest(val paymentStatus: String)

data class RevenueNoteRequest(val note: String?)

// ── Response DTOs ──────────────────────────────────────────────────────────────

data class RevenuePartyResponse(
    val nip: String?,
    val name: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val countryCode: String?,
    /** Rachunek do przelewu — wypełniany tylko po stronie sprzedawcy. */
    val bankAccount: String? = null
)

data class RevenueItemResponse(
    val lineNumber: Int,
    val name: String,
    val unit: String?,
    val quantity: BigDecimal,
    val unitPriceNet: Long,
    val netValue: Long,
    val vatValue: Long,
    val grossValue: Long,
    val vatRate: String
)

data class RevenueInvoiceResponse(
    val id: String,
    val source: String,               // CRM | EXTERNAL
    val ksefStatus: String,           // PENDING | SENDING | SUBMITTED | ACCEPTED | REJECTED | QUEUED_RETRY
    val invoiceNumber: String,
    val ksefNumber: String?,
    val invoiceType: String,          // VAT | KOR
    val originalInvoiceId: String?,
    val originalKsefNumber: String?,
    val correctionReason: String?,
    val issueDate: LocalDate,
    val saleDate: LocalDate?,
    val seller: RevenuePartyResponse,
    val buyer: RevenuePartyResponse,
    val totalNet: Long,               // grosze
    val totalVat: Long,               // grosze
    val totalGross: Long,             // grosze
    val currency: String,
    val paymentForm: String?,
    val paymentFormLabel: String?,
    val paymentStatus: String,        // PAID | PENDING
    val paymentDueDate: LocalDate?,
    val duplicateStatus: String,      // NONE | SUSPECTED | CONFIRMED_DUPLICATE | DISMISSED
    val duplicateOfId: String?,
    val hasUpo: Boolean,
    val hasXml: Boolean,
    val sendAttempts: Int,
    val lastSendError: String?,
    val sentAt: Instant?,
    val acceptedAt: Instant?,
    val visitId: String?,
    val customerId: String?,
    val description: String?,
    val note: String?,
    /** Ukryta ręcznie ze statystyk i z domyślnej listy dokumentów. */
    val excluded: Boolean,
    val excludedAt: Instant?,
    /**
     * Czy faktura ma już pobrane szczegóły z XML (pozycje, adresy, płatność).
     * false tylko dla faktur pobranych z KSeF, którym XML dołoży synchronizacja
     * wsteczna — UI mówi wtedy „uzupełnimy", zamiast udawać, że pozycji nie ma.
     */
    val detailsSynced: Boolean,
    /**
     * Adres weryfikacyjny KSeF („KOD I") do wystawienia jako kod QR na wizualizacji
     * faktury. null, gdy faktura nie ma jeszcze skrótu dokumentu (np. przed wysyłką).
     */
    val ksefVerificationUrl: String?,
    val createdAt: Instant,
    val items: List<RevenueItemResponse>?
)

data class RevenueInvoiceListResponse(
    val invoices: List<RevenueInvoiceResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class RevenueTotalsResponse(
    val gross: Long,
    val net: Long,
    val vat: Long,
    val invoiceCount: Long,
    val correctionCount: Long,
    val externalCount: Long
)

data class MonthlyRevenueResponse(
    val month: String,
    val gross: Long,
    val net: Long,
    val vat: Long,
    val invoiceCount: Long,
    val correctionCount: Long,
    val externalCount: Long
)

data class RevenueStatisticsResponse(
    val year: Int,
    val totals: RevenueTotalsResponse,
    val monthly: List<MonthlyRevenueResponse>,
    val pendingKsefCount: Long
)
