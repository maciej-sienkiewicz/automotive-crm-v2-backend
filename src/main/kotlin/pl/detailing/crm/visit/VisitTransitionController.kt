package pl.detailing.crm.visit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.transitions.markready.*
import pl.detailing.crm.visit.transitions.complete.*
import pl.detailing.crm.visit.transitions.reject.*
import pl.detailing.crm.visit.transitions.archive.*
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission
import java.time.LocalDate

/**
 * REST Controller for visit state transitions
 *
 * Provides dedicated endpoints for each state transition:
 * - Mark as ready for pickup
 * - Complete (hand over to customer) – auto-issues a financial document
 * - Reject
 * - Archive
 */
@RestController
@RequestMapping("/api/visits")
@RequiresPermission(Permission.VISITS_VIEW)
class VisitTransitionController(
    private val markVisitReadyForPickupHandler: MarkVisitReadyForPickupHandler,
    private val completeVisitHandler: CompleteVisitHandler,
    private val completeVisitInvoiceOrchestrator: CompleteVisitInvoiceOrchestrator,
    private val rejectVisitHandler: RejectVisitHandler,
    private val archiveVisitHandler: ArchiveVisitHandler
) {

    /**
     * Mark visit as ready for pickup
     * POST /api/visits/{visitId}/mark-ready-for-pickup
     *
     * Transition: IN_PROGRESS → READY_FOR_PICKUP
     * Requires: All services must be completed or rejected
     * Access: OWNER, MANAGER
     */
    @PostMapping("/{visitId}/mark-ready-for-pickup")
    fun markReadyForPickup(
        @PathVariable visitId: String,
        @RequestBody request: MarkReadyForPickupRequest
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = MarkVisitReadyForPickupCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId),
            sendSms = request.sms,
            sendEmail = request.email,
            userName = principal.fullName
        )

        val result = markVisitReadyForPickupHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit marked as ready for pickup"
        ))
    }

    /**
     * Complete visit – hand over vehicle to customer and issue a financial document.
     * POST /api/visits/{visitId}/complete
     *
     * Transition: READY_FOR_PICKUP → COMPLETED
     *
     * A financial document (receipt / invoice) is automatically created for all
     * CONFIRMED and APPROVED service items.  Pass an optional [CompleteVisitRequest]
     * body to control payment method, document type and counterparty data.
     * Omitting the body defaults to CASH + RECEIPT with no counterparty.
     *
     * Access: OWNER, MANAGER
     */
    @PostMapping("/{visitId}/complete")
    fun completeVisit(
        @PathVariable visitId: String,
        @RequestBody request: CompleteVisitRequest
    ): ResponseEntity<CompleteVisitResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val paymentMethod = parsePaymentMethod(request.payment.method)
        val documentType  = request.payment.invoiceType
            ?.let { parseDocumentType(it) }
            ?: DocumentType.RECEIPT

        val command = CompleteVisitCommand(
            studioId          = principal.studioId,
            userId            = principal.userId,
            visitId           = VisitId.fromString(visitId),
            userName          = principal.fullName,
            signatureObtained = request.signatureObtained,
            paymentMethod     = paymentMethod,
            documentType      = documentType,
            dueDate           = request.payment.dueDate
        )

        // Faktura KSeF z edytowanymi pozycjami i (opcjonalnie) dokumentem reszty —
        // orkiestrator pilnuje, że faktura + reszta == kwota wizyty (całość udokumentowana)
        val invoiceDetails = request.invoice?.takeIf { documentType == DocumentType.INVOICE }
        if (invoiceDetails != null) {
            val result = completeVisitInvoiceOrchestrator.handle(
                command,
                CompleteInvoiceDetails(
                    items = invoiceDetails.items.map {
                        CompleteInvoiceItem(
                            name           = it.name,
                            quantity       = it.quantity ?: java.math.BigDecimal.ONE,
                            unitPriceNet   = it.unitPriceNet,
                            unitPriceGross = it.unitPriceGross,
                            vatRate        = it.vatRate ?: "23"
                        )
                    },
                    buyer = CompleteInvoiceBuyer(
                        nip          = invoiceDetails.buyerNip,
                        name         = invoiceDetails.buyerName,
                        addressLine1 = invoiceDetails.buyerAddressLine1,
                        addressLine2 = invoiceDetails.buyerAddressLine2,
                        email        = invoiceDetails.buyerEmail
                    ),
                    remainderPaymentMethod = invoiceDetails.remainderPaymentMethod
                        ?.let { parsePaymentMethod(it) },
                    exemptionLegalBasis = invoiceDetails.exemptionLegalBasis
                )
            )
            return@runBlocking ResponseEntity.ok(
                CompleteVisitResponse(
                    visitId                 = result.completion.visitId.value.toString(),
                    newStatus               = mapVisitStatus(result.completion.newStatus),
                    message                 = "Visit completed successfully",
                    financialDocumentId     = result.completion.financialDocumentId?.toString(),
                    financialDocumentNumber = result.completion.financialDocumentNumber,
                    ksefInvoiceId           = result.ksefInvoice.id.toString(),
                    ksefInvoiceNumber       = result.ksefInvoice.invoiceNumber,
                    ksefStatus              = result.ksefInvoice.ksefStatus.name,
                    remainderDocumentNumber = result.remainderDocumentNumber
                )
            )
        }

        val result = completeVisitHandler.handle(command)

        ResponseEntity.ok(
            CompleteVisitResponse(
                visitId                 = result.visitId.value.toString(),
                newStatus               = mapVisitStatus(result.newStatus),
                message                 = "Visit completed successfully",
                financialDocumentId     = result.financialDocumentId?.toString(),
                financialDocumentNumber = result.financialDocumentNumber
            )
        )
    }

    /**
     * Reject visit
     * POST /api/visits/{visitId}/reject
     *
     * Transition: IN_PROGRESS → REJECTED
     * Access: OWNER, MANAGER
     */
    @PostMapping("/{visitId}/reject")
    fun rejectVisit(
        @PathVariable visitId: String,
        @RequestBody request: RejectVisitRequest
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = RejectVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId),
            rejectionReason = request.reason,
            userName = principal.fullName
        )

        val result = rejectVisitHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit rejected"
        ))
    }

    /**
     * Archive visit
     * POST /api/visits/{visitId}/archive
     *
     * Transition: COMPLETED → ARCHIVED or REJECTED → ARCHIVED
     * Access: OWNER
     */
    @PostMapping("/{visitId}/archive")
    fun archiveVisit(
        @PathVariable visitId: String
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Tylko właściciel może archiwizować wizyty")
        }

        val command = ArchiveVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId),
            userName = principal.fullName
        )

        val result = archiveVisitHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit archived"
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun mapVisitStatus(status: VisitStatus): String = when (status) {
        VisitStatus.IN_PROGRESS       -> "in_progress"
        VisitStatus.READY_FOR_PICKUP  -> "ready_for_pickup"
        VisitStatus.COMPLETED         -> "completed"
        VisitStatus.REJECTED          -> "rejected"
        VisitStatus.ARCHIVED          -> "archived"
        VisitStatus.DRAFT             -> "draft"
    }

    private fun parsePaymentMethod(value: String): PaymentMethod =
        runCatching { PaymentMethod.valueOf(value.uppercase()) }.getOrElse {
            throw ValidationException(
                "Nieprawidłowa metoda płatności: '$value'. Dozwolone: ${PaymentMethod.entries.joinToString { it.name }}"
            )
        }

    private fun parseDocumentType(value: String): DocumentType =
        runCatching { DocumentType.valueOf(value.uppercase()) }.getOrElse {
            throw ValidationException(
                "Nieprawidłowy typ dokumentu: '$value'. Dozwolone: ${DocumentType.entries.joinToString { it.name }}"
            )
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class MarkReadyForPickupRequest(
    val sms: Boolean,
    val email: Boolean
)

data class RejectVisitRequest(
    val reason: String?
)

/**
 * Request body for `POST /api/visits/{visitId}/complete`.
 *
 * Example – invoice paid by bank transfer:
 * ```json
 * {
 *   "signatureObtained": true,
 *   "payment": {
 *     "method": "TRANSFER",
 *     "invoiceType": "INVOICE",
 *     "dueDate": "2024-08-14",
 *     "amount": 172200
 *   }
 * }
 * ```
 * Buyer data (NIP, address, e-mail) are resolved automatically from the Customer record.
 */
data class CompleteVisitRequest(
    val signatureObtained: Boolean = false,
    val payment: PaymentRequest,

    /**
     * Szczegóły faktury KSeF (tylko gdy payment.invoiceType == INVOICE) — pozycje
     * po ewentualnej edycji przez użytkownika. Gdy suma pozycji jest niższa niż
     * kwota wizyty, wymagane [CompleteInvoiceRequestDto.remainderPaymentMethod] —
     * reszta zostaje udokumentowana paragonem (osobnym dokumentem przychodowym).
     */
    val invoice: CompleteInvoiceRequestDto? = null
)

data class CompleteInvoiceItemDto(
    val name: String,
    val quantity: java.math.BigDecimal? = null,
    /** Cena jednostkowa netto w groszach — dokładnie jedno z pól netto/brutto. */
    val unitPriceNet: Long? = null,
    /** Cena jednostkowa brutto w groszach — brutto pozostaje dokładnie jak wpisane. */
    val unitPriceGross: Long? = null,
    /** 23 | 8 | 5 | 0 | zw (domyślnie 23). */
    val vatRate: String? = null
)

data class CompleteInvoiceRequestDto(
    val items: List<CompleteInvoiceItemDto>,
    val buyerNip: String? = null,
    val buyerName: String? = null,
    val buyerAddressLine1: String? = null,
    val buyerAddressLine2: String? = null,
    val buyerEmail: String? = null,
    /** Metoda płatności dokumentu reszty (CASH | CARD | ...) — wymagana gdy faktura < kwota wizyty. */
    val remainderPaymentMethod: String? = null,
    val exemptionLegalBasis: String? = null
)

data class PaymentRequest(
    /** CASH | CARD | TRANSFER | BLIK_NA_NUMER | BLIK_TERMINAL */
    val method: String,

    /** RECEIPT | INVOICE (default: RECEIPT when absent) */
    val invoiceType: String? = null,

    /** Required when method == TRANSFER. */
    val dueDate: LocalDate? = null,

    /** Gross amount in grosz sent by the frontend (informational). */
    val amount: Long? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class VisitStatusChangeResponse(
    val visitId: String,
    val newStatus: String,
    val message: String
)

/**
 * Response for `POST /api/visits/{visitId}/complete`.
 *
 * Extends the standard status-change response with financial document details.
 */
data class CompleteVisitResponse(
    val visitId: String,
    val newStatus: String,
    val message: String,

    /** UUID of the auto-issued financial document. Null if the visit had no service items. */
    val financialDocumentId: String?,

    /** Human-readable document number, e.g. "PAR/2024/0001". */
    val financialDocumentNumber: String?,

    /** Id faktury KSeF (moduł przychodowy) — tylko przy zakończeniu z fakturą. */
    val ksefInvoiceId: String? = null,

    /** Numer własny faktury KSeF, np. FV/2026/0001. */
    val ksefInvoiceNumber: String? = null,

    /** Status wysyłki do KSeF: ACCEPTED | SUBMITTED | QUEUED_RETRY | ... */
    val ksefStatus: String? = null,

    /** Numer paragonu dokumentującego resztę kwoty (gdy faktura była częściowa). */
    val remainderDocumentNumber: String? = null
)
