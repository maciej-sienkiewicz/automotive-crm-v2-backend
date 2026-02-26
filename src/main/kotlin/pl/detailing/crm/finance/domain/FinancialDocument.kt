package pl.detailing.crm.finance.domain

import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// Domain Enums
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Type of financial document.
 *
 * [prefix] – used to build human-readable document numbers, e.g. PAR/2024/0001.
 */
enum class DocumentType(val prefix: String, val displayName: String) {
    RECEIPT("PAR",  "Paragon"),
    INVOICE("FAK",  "Faktura"),
    OTHER("DOK",    "Dokument")
}

/**
 * Cash-flow direction of a financial document.
 *
 * INCOME  = money flowing INTO the studio (sale to customer).
 * EXPENSE = money flowing OUT  of the studio (purchase from supplier).
 */
enum class DocumentDirection(val displayName: String) {
    INCOME("Przychód"),
    EXPENSE("Koszt")
}

/**
 * Payment / settlement status of a document.
 *
 * PAID     – fully settled; set automatically for CASH and CARD payments.
 * PENDING  – awaiting settlement; default for TRANSFER payments.
 * OVERDUE  – past due date without payment; updated by scheduler or manually.
 */
enum class DocumentStatus(val displayName: String) {
    PAID("Opłacony"),
    PENDING("Oczekujący"),
    OVERDUE("Przeterminowany")
}

/**
 * Payment method chosen at document creation.
 *
 * Only [CASH] triggers an automatic update of the studio's cash-register balance.
 * [CARD] and [TRANSFER] do NOT affect the cash register.
 */
enum class PaymentMethod(val displayName: String) {
    CASH("Gotówka"),
    CARD("Karta"),
    TRANSFER("Przelew");

    /** Derives the initial [DocumentStatus] that should be applied when using this method. */
    fun defaultStatus(): DocumentStatus = when (this) {
        TRANSFER -> DocumentStatus.PENDING
        else     -> DocumentStatus.PAID
    }

    /** Returns true if this payment method directly affects the physical cash register. */
    fun affectsCashRegister(): Boolean = this == CASH
}

// ─────────────────────────────────────────────────────────────────────────────
// Domain Model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Core domain model for a financial document (receipt, invoice, or other).
 *
 * Multi-tenancy: every document is strictly scoped to [studioId].
 *
 * Monetary fields are always stored in grosz (1/100 PLN) as [Money].
 * Invariant: [totalNet] + [totalVat] == [totalGross].
 *
 * KSeF placeholders ([ksefInvoiceId], [ksefNumber]) are reserved for future
 * integration with the Polish e-invoicing system; they are nullable and have
 * no business logic attached yet.
 */
data class FinancialDocument(
    val id: FinancialDocumentId,
    val studioId: StudioId,

    /** Optional link to the visit this document was issued for. */
    val visitId: VisitId?,

    /** Human-readable document number, e.g. "PAR/2024/0001". */
    val documentNumber: String,

    val documentType: DocumentType,
    val direction: DocumentDirection,
    val status: DocumentStatus,
    val paymentMethod: PaymentMethod,

    val totalNet: Money,
    val totalVat: Money,
    val totalGross: Money,

    /** ISO-4217 currency code; PLN for all domestic documents. */
    val currency: String,

    val issueDate: LocalDate,

    /** Payment due date – relevant for TRANSFER invoices; null for immediate payments. */
    val dueDate: LocalDate?,

    /** Timestamp when the document was marked as paid; null if still PENDING/OVERDUE. */
    val paidAt: Instant?,

    val description: String?,

    /** Name of the buyer (INCOME) or seller (EXPENSE). */
    val counterpartyName: String?,

    /** NIP (Polish tax ID) of the counterparty. */
    val counterpartyNip: String?,

    // ── KSeF integration placeholders ──────────────────────────────────────
    /** Future: link to the ksef_invoices table once KSeF integration is enabled. */
    val ksefInvoiceId: UUID?,

    /** Future: KSeF reference number assigned by the Ministry of Finance. */
    val ksefNumber: String?,

    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        require(totalNet.amountInCents + totalVat.amountInCents == totalGross.amountInCents) {
            "Financial integrity violation: totalNet ($totalNet) + totalVat ($totalVat) ≠ totalGross ($totalGross)"
        }
    }
}
