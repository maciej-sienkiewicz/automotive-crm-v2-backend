package pl.detailing.crm.finance.income

import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import org.springframework.web.bind.annotation.RequestBody
import pl.detailing.crm.finance.document.UpdateDocumentStatusCommand
import pl.detailing.crm.finance.document.UpdateDocumentStatusHandler
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.finance.payment.BulkPaymentStatusPlan
import pl.detailing.crm.finance.payment.BulkPaymentStatusResponse
import pl.detailing.crm.finance.payment.BulkPaymentStatusSkip
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.SearchTerm
import java.util.UUID
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

/**
 * Zunifikowana lista dokumentów przychodowych — faktury i korekty z ledgera KSeF
 * razem z paragonami i dokumentami „inne" z modułu finansowego, w jednym,
 * stronicowanym widoku.
 */
@RequiresCapability(CapabilityKey.FINANCE_ACCESS)
@RestController
@RequestMapping("/api/v1/finance/income-documents")
@RequiresPermission(Permission.FINANCE_INVOICES)
class IncomeDocumentsController(
    private val repository: IncomeDocumentsRepository,
    private val revenueInvoiceRepository: KsefRevenueInvoiceRepository,
    private val financialDocumentRepository: FinancialDocumentRepository,
    private val updateDocumentStatusHandler: UpdateDocumentStatusHandler
) {

    companion object {
        private val DOCUMENT_TYPES = setOf("INVOICE", "CORRECTION", "RECEIPT", "OTHER")
        private val PAYMENT_STATUSES = setOf("PAID", "PENDING", "OVERDUE")
        private val SOURCE_KINDS = setOf("KSEF", "FINANCE")
        /** Górny limit jednej operacji grupowej — tyle, ile realnie mieści się na liście. */
        private const val MAX_BULK_DOCUMENTS = 200
    }

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) documentType: String?,
        @RequestParam(required = false) paymentStatus: String?,
        @RequestParam(required = false) dateFrom: LocalDate?,
        @RequestParam(required = false) dateTo: LocalDate?,
        @RequestParam(defaultValue = "false") onlyKsef: Boolean,
        @RequestParam(defaultValue = "false") includeExcluded: Boolean,
        /**
         * Jedna fraza „szukaj": NIP, nazwa kontrahenta, nazwa pozycji, numer dokumentu,
         * numer KSeF albo kwota. Rozkład frazy na porównywalne warianty robi [SearchTerm].
         */
        @RequestParam(required = false) search: String?
    ): ResponseEntity<IncomeDocumentListResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value

        val type = documentType?.uppercase()?.also {
            if (it !in DOCUMENT_TYPES) {
                throw ValidationException("Nieprawidłowy typ dokumentu: '$documentType'. Dozwolone: ${DOCUMENT_TYPES.joinToString()}")
            }
        }
        val status = paymentStatus?.uppercase()?.also {
            if (it !in PAYMENT_STATUSES) {
                throw ValidationException("Nieprawidłowy status płatności: '$paymentStatus'. Dozwolone: ${PAYMENT_STATUSES.joinToString()}")
            }
        }

        val pageSize = size.coerceIn(1, 100)
        val pageNumber = maxOf(1, page)
        val filters = IncomeDocumentFilters(
            studioId      = studioId,
            documentType  = type,
            paymentStatus = status,
            dateFrom      = dateFrom,
            dateTo        = dateTo,
            onlyKsef      = onlyKsef,
            includeExcluded = includeExcluded,
            search        = SearchTerm.like(search),
            searchDigits  = SearchTerm.digitsLike(search),
            searchAmount  = SearchTerm.amountLike(search)
        )

        val rows = repository.findPage(filters, limit = pageSize, offset = (pageNumber - 1) * pageSize)

        return ResponseEntity.ok(
            IncomeDocumentListResponse(
                documents = rows.map { it.toResponse() },
                total     = repository.count(filters),
                page      = pageNumber,
                pageSize  = pageSize
            )
        )
    }

    /**
     * Ukrywa dokument przychodowy: znika ze statystyk (kafle, raporty) i z domyślnej
     * listy, ale zostaje w bazie i wraca po przywróceniu. Odpowiednik ukrywania
     * dokumentów kosztowych — z tą różnicą, że lista przychodów łączy dwa źródła,
     * więc o docelowej tabeli decyduje [sourceKind] z wiersza listy.
     *
     * To operacja prezentacyjna, nie księgowa: faktura przyjęta w KSeF pozostaje
     * prawnie wiążąca i koryguje się ją fakturą korygującą, a nie ukryciem.
     */
    @PatchMapping("/{sourceKind}/{id}/exclude")
    @Transactional
    fun exclude(@PathVariable sourceKind: String, @PathVariable id: UUID): ResponseEntity<Void> =
        setExcluded(sourceKind, id, excluded = true)

    /** Przywraca ukryty dokument do statystyk i domyślnej listy. */
    @PatchMapping("/{sourceKind}/{id}/restore")
    @Transactional
    fun restore(@PathVariable sourceKind: String, @PathVariable id: UUID): ResponseEntity<Void> =
        setExcluded(sourceKind, id, excluded = false)

    /**
     * Grupowa zmiana statusu płatności dla zaznaczonych dokumentów przychodowych.
     *
     * Lista łączy dwa źródła, więc i ta operacja musi: faktura z ledgera KSeF dostaje
     * status wprost (tam jest on adnotacją księgową i cofa się swobodnie), a dokument
     * modułu finansowego idzie przez [UpdateDocumentStatusHandler], bo tylko on zna
     * regułę „opłaconego nie da się cofnąć" i zapisuje wpis do audytu.
     *
     * Dokumenty, których nie wolno ruszyć, odsiewa [BulkPaymentStatusPlan] ZANIM
     * cokolwiek zostanie zapisane. Gdyby zamiast tego łapać wyjątek z handlera,
     * transakcja byłaby już oznaczona jako rollback-only i przepadłaby cała operacja —
     * a nie po to zaznacza się dwadzieścia faktur, żeby jedna opłacona wywróciła resztę.
     */
    @PatchMapping("/payment-status")
    @Transactional
    fun updatePaymentStatus(
        @RequestBody request: BulkIncomePaymentStatusRequest
    ): ResponseEntity<BulkPaymentStatusResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value

        if (request.documents.isEmpty()) throw ValidationException("Nie wskazano żadnego dokumentu")
        if (request.documents.size > MAX_BULK_DOCUMENTS) {
            throw ValidationException("Jednorazowo można zmienić status najwyżej $MAX_BULK_DOCUMENTS dokumentów")
        }
        val target = request.paymentStatus.uppercase()
        if (target !in BulkPaymentStatusPlan.TARGETS) {
            throw ValidationException("paymentStatus musi być PAID lub PENDING")
        }

        val requested = request.documents.distinct()
        requested.forEach {
            if (it.sourceKind.uppercase() !in SOURCE_KINDS) {
                throw ValidationException("Nieprawidłowe źródło dokumentu: '${it.sourceKind}'. Dozwolone: KSEF, FINANCE")
            }
        }

        val ksefIds    = requested.filter { it.sourceKind.uppercase() == "KSEF" }.map { it.id }
        val financeIds = requested.filter { it.sourceKind.uppercase() == "FINANCE" }.map { it.id }

        val ksefInvoices = if (ksefIds.isEmpty()) emptyList()
                           else revenueInvoiceRepository.findByIdInAndStudioId(ksefIds, studioId)
        val financeDocuments = if (financeIds.isEmpty()) emptyList()
                               else financialDocumentRepository.findAllByIdInAndStudioId(financeIds, studioId)

        val found =
            ksefInvoices.map { BulkPaymentStatusPlan.Item(key("KSEF", it.id), it.paymentStatus) } +
            financeDocuments.map {
                BulkPaymentStatusPlan.Item(key("FINANCE", it.id), it.status.name, paidIsFinal = true)
            }

        val plan = BulkPaymentStatusPlan.of(
            requested = requested.map { key(it.sourceKind.uppercase(), it.id) },
            found     = found,
            target    = target
        )
        val toChange = plan.toChange.toSet()

        ksefInvoices
            .filter { key("KSEF", it.id) in toChange }
            .forEach {
                it.applyPaymentStatus(target)
                revenueInvoiceRepository.save(it)
            }

        financeDocuments
            .filter { key("FINANCE", it.id) in toChange }
            .forEach {
                updateDocumentStatusHandler.handle(
                    UpdateDocumentStatusCommand(
                        studioId        = principal.studioId,
                        userId          = principal.userId,
                        userDisplayName = principal.fullName,
                        documentId      = FinancialDocumentId(it.id),
                        newStatus       = DocumentStatus.valueOf(target)
                    )
                )
            }

        return ResponseEntity.ok(
            BulkPaymentStatusResponse(
                updated   = plan.toChange.size,
                unchanged = plan.unchanged.size,
                skipped   = plan.skipped.map { BulkPaymentStatusSkip(it.key.substringAfter(':'), it.reason) }
            )
        )
    }

    /** Klucz pozycji w planie — id samo w sobie nie wystarcza, bo lista łączy dwa źródła. */
    private fun key(sourceKind: String, id: UUID) = "$sourceKind:$id"

    private fun setExcluded(sourceKind: String, id: UUID, excluded: Boolean): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value
        val userId = principal.userId.value

        when (sourceKind.uppercase()) {
            "KSEF" -> {
                val invoice = revenueInvoiceRepository.findByIdAndStudioId(id, studioId)
                    ?: throw NotFoundException("Faktura przychodowa $id nie istnieje")
                if (invoice.isExcluded != excluded) {
                    if (excluded) invoice.markExcluded(userId) else invoice.markRestored()
                    revenueInvoiceRepository.save(invoice)
                }
            }
            "FINANCE" -> {
                val document = financialDocumentRepository.findByIdAndStudioId(id, studioId)
                    ?: throw NotFoundException("Dokument przychodowy $id nie istnieje")
                if (document.isExcluded != excluded) {
                    if (excluded) document.markExcluded(userId) else document.markRestored(userId)
                    financialDocumentRepository.save(document)
                }
            }
            else -> throw ValidationException("Nieprawidłowe źródło dokumentu: '$sourceKind'. Dozwolone: KSEF, FINANCE")
        }
        return ResponseEntity.noContent().build()
    }

    private fun IncomeDocumentRow.toResponse() = IncomeDocumentResponse(
        id               = id,
        sourceKind       = sourceKind,
        documentType     = documentType,
        documentNumber   = documentNumber,
        issueDate        = issueDate,
        counterpartyName = counterpartyName,
        counterpartyNip  = counterpartyNip,
        totalNet         = totalNet,
        totalVat         = totalVat,
        totalGross       = totalGross,
        currency         = currency,
        paymentStatus    = paymentStatus,
        paymentLabel     = paymentLabel,
        ksefStatus       = ksefStatus,
        ksefNumber       = ksefNumber,
        origin           = origin,
        duplicateStatus  = duplicateStatus,
        visitId          = visitId,
        createdAt        = createdAt,
        excluded         = excluded
    )
}

/** Jeden zaznaczony wiersz listy: id samo w sobie nie wystarcza, bo źródła są dwa. */
data class IncomeDocumentRef(
    val sourceKind: String,
    val id: UUID
)

data class BulkIncomePaymentStatusRequest(
    val documents: List<IncomeDocumentRef>,
    /** PAID | PENDING */
    val paymentStatus: String
)

data class IncomeDocumentResponse(
    val id: String,
    /** KSEF | FINANCE — decyduje, który widok szczegółów otworzyć. */
    val sourceKind: String,
    /** INVOICE | CORRECTION | RECEIPT | OTHER */
    val documentType: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val counterpartyName: String?,
    val counterpartyNip: String?,
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val currency: String,
    val paymentStatus: String,
    val paymentLabel: String?,
    val ksefStatus: String?,
    val ksefNumber: String?,
    val origin: String?,
    val duplicateStatus: String,
    val visitId: String?,
    val createdAt: Instant,
    /** Ukryty ręcznie ze statystyk — widoczny tylko przy includeExcluded=true. */
    val excluded: Boolean
)

data class IncomeDocumentListResponse(
    val documents: List<IncomeDocumentResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)
