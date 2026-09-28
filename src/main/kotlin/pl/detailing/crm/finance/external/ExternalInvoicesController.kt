package pl.detailing.crm.finance.external

import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.pii.Pii
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Lista „Do zafakturowania" — tryb „Faktury wystawia księgowość".
 *
 * GET    /api/v1/finance/external-invoices                — zgłoszenia (domyślnie czekające)
 * GET    /api/v1/finance/external-invoices/pending-count  — licznik do zakładki
 * POST   /api/v1/finance/external-invoices/{id}/issued    — „Faktura wystawiona" (numer opcjonalny)
 * DELETE /api/v1/finance/external-invoices/{id}/issued    — cofnięcie odhaczenia
 */
@RequiresCapability(CapabilityKey.FINANCE_ACCESS)
@RestController
@RequestMapping("/api/v1/finance/external-invoices")
@RequiresPermission(Permission.FINANCE_INVOICES)
class ExternalInvoicesController(
    private val repository: ExternalInvoiceRequestRepository,
    private val service: ExternalInvoiceRequestService,
    private val documentRepository: FinancialDocumentRepository,
    private val visitRepository: VisitRepository
) {

    @GetMapping
    fun list(
        /** PENDING | ISSUED | ALL — ALL bez wycofanych, które niczego od księgowości nie chcą. */
        @RequestParam(defaultValue = "PENDING") status: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): ResponseEntity<ExternalInvoiceListResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val statuses = when (status.uppercase()) {
            "PENDING" -> listOf(ExternalInvoiceStatus.PENDING)
            "ISSUED" -> listOf(ExternalInvoiceStatus.ISSUED)
            "ALL" -> listOf(ExternalInvoiceStatus.PENDING, ExternalInvoiceStatus.ISSUED)
            else -> throw ValidationException("Nieprawidłowy status: '$status'. Dozwolone: PENDING, ISSUED, ALL")
        }
        val pageSize = size.coerceIn(1, 100)
        val pageNumber = maxOf(1, page)
        val result = repository.findPage(studioId, statuses, PageRequest.of(pageNumber - 1, pageSize))
        val requests = result.content

        val documents = documentRepository.findAllById(requests.map { it.financialDocumentId }.toSet()).associateBy { it.id }
        val visits = visitRepository.findAllById(requests.mapNotNull { it.visitId }.toSet()).associateBy { it.id }
        val corrected = repository.findAllById(requests.mapNotNull { it.correctsRequestId }.toSet()).associateBy { it.id }

        return ResponseEntity.ok(
            ExternalInvoiceListResponse(
                items = requests.map { request ->
                    val document = documents[request.financialDocumentId]
                    val visit = request.visitId?.let { visits[it] }
                    ExternalInvoiceRowResponse(
                        id = request.id.toString(),
                        kind = request.kind.name,
                        kindLabel = request.kind.displayName,
                        status = request.status.name,
                        statusLabel = request.status.displayName,
                        visitId = request.visitId?.toString(),
                        visitNumber = visit?.visitNumber,
                        visitDeleted = visit?.deletedAt != null,
                        vehicleLabel = listOfNotNull(visit?.brandSnapshot, visit?.modelSnapshot)
                            .joinToString(" ").ifBlank { null },
                        licensePlate = visit?.licensePlateSnapshot,
                        documentId = request.financialDocumentId.toString(),
                        documentNumber = document?.documentNumber,
                        paymentMethod = document?.paymentMethod?.name,
                        paymentMethodLabel = document?.paymentMethod?.displayName,
                        paymentStatus = document?.status?.name,
                        paymentStatusLabel = document?.status?.displayName,
                        saleDate = document?.issueDate?.toString(),
                        dueDate = document?.dueDate?.toString(),
                        buyerNip = request.buyerNip,
                        buyerName = request.buyerName,
                        buyerAddressLine1 = request.buyerAddressLine1,
                        buyerAddressLine2 = request.buyerAddressLine2,
                        buyerEmail = request.buyerEmail,
                        totalNet = request.totalNet,
                        totalVat = request.totalVat,
                        totalGross = request.totalGross,
                        externalInvoiceNumber = request.externalInvoiceNumber,
                        issuedAt = request.issuedAt,
                        issuedByName = request.issuedByName,
                        correctsInvoiceNumber = request.correctsRequestId?.let { corrected[it]?.externalInvoiceNumber },
                        createdAt = request.createdAt
                    )
                },
                total = result.totalElements,
                page = pageNumber,
                pageSize = pageSize
            )
        )
    }

    @GetMapping("/pending-count")
    fun pendingCount(): ResponseEntity<ExternalInvoicePendingCountResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        return ResponseEntity.ok(
            ExternalInvoicePendingCountResponse(repository.countByStudioIdAndStatus(studioId, ExternalInvoiceStatus.PENDING))
        )
    }

    @PostMapping("/{id}/issued")
    fun markIssued(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: MarkExternalInvoiceIssuedRequest?
    ): ResponseEntity<ExternalInvoiceStatusResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val saved = service.markIssued(principal.studioId, id, request?.invoiceNumber, principal.userId, principal.fullName)
        return ResponseEntity.ok(saved.toStatusResponse())
    }

    @DeleteMapping("/{id}/issued")
    fun unmarkIssued(@PathVariable id: UUID): ResponseEntity<ExternalInvoiceStatusResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val saved = service.unmarkIssued(principal.studioId, id, principal.userId, principal.fullName)
        return ResponseEntity.ok(saved.toStatusResponse())
    }

    private fun ExternalInvoiceRequestEntity.toStatusResponse() = ExternalInvoiceStatusResponse(
        id = id.toString(),
        status = status.name,
        statusLabel = status.displayName,
        externalInvoiceNumber = externalInvoiceNumber,
        issuedAt = issuedAt,
        issuedByName = issuedByName
    )
}

data class MarkExternalInvoiceIssuedRequest(val invoiceNumber: String? = null)

data class ExternalInvoiceStatusResponse(
    val id: String,
    val status: String,
    val statusLabel: String,
    val externalInvoiceNumber: String?,
    val issuedAt: Instant?,
    val issuedByName: String?
)

data class ExternalInvoicePendingCountResponse(val pending: Long)

data class ExternalInvoiceListResponse(
    val items: List<ExternalInvoiceRowResponse>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

data class ExternalInvoiceRowResponse(
    val id: String,
    /** INVOICE | INVOICE_TO_RECEIPT | CORRECTION */
    val kind: String,
    val kindLabel: String,
    /** PENDING | ISSUED */
    val status: String,
    val statusLabel: String,
    val visitId: String?,
    val visitNumber: String?,
    /**
     * Wizytę usunięto, a sprzedaż została: usunięcie wizyty nie rusza Finansów (zapis
     * płatności i kasa zostają). Lista mówi to wprost, zamiast linkować do wizyty, której
     * nie ma; czy fakturę jednak wystawić, czy usunąć zapis płatności, decyduje człowiek.
     */
    val visitDeleted: Boolean = false,
    val vehicleLabel: String?,
    val licensePlate: String?,
    val documentId: String,
    /** Numer dokumentu w CRM (zapis płatności) — nie numer faktury. */
    val documentNumber: String?,
    val paymentMethod: String?,
    val paymentMethodLabel: String?,
    /** Status płatności dokumentu w CRM: PAID | PENDING | OVERDUE. */
    val paymentStatus: String?,
    val paymentStatusLabel: String?,
    /** Data sprzedaży: dzień wydania pojazdu albo poprawki rozliczenia. */
    val saleDate: String?,
    val dueDate: String?,
    @Pii val buyerNip: String?,
    @Pii val buyerName: String?,
    @Pii val buyerAddressLine1: String?,
    @Pii val buyerAddressLine2: String?,
    @Pii val buyerEmail: String?,
    /** Kwoty w groszach; korekta ma kwoty ujemne faktury, którą koryguje. */
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val externalInvoiceNumber: String?,
    val issuedAt: Instant?,
    val issuedByName: String?,
    /** Korekta: numer faktury księgowości, którą koryguje (jeśli go wpisano). */
    val correctsInvoiceNumber: String?,
    val createdAt: Instant
)
