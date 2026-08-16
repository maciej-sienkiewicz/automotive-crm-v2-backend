package pl.detailing.crm.finance.income

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
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
    private val repository: IncomeDocumentsRepository
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
        @RequestParam(defaultValue = "false") onlyKsef: Boolean
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
            onlyKsef      = onlyKsef
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
        createdAt        = createdAt
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
    val createdAt: Instant
)

data class IncomeDocumentListResponse(
    val documents: List<IncomeDocumentResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)
