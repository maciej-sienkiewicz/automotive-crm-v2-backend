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
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.NotFoundException
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
    private val financialDocumentRepository: FinancialDocumentRepository
) {

    companion object {
        private val DOCUMENT_TYPES = setOf("INVOICE", "CORRECTION", "RECEIPT", "OTHER")
        private val PAYMENT_STATUSES = setOf("PAID", "PENDING", "OVERDUE")
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
        @RequestParam(defaultValue = "false") includeExcluded: Boolean
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
            includeExcluded = includeExcluded
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
