package pl.detailing.crm.finance.domain

import pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
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

/**
 * Origin of the document – how it entered the system.
 *
 * VISIT    – auto-created when a visit is completed ([CompleteVisitHandler]).
 * KSEF     – imported from the Polish e-invoicing system (KSeF sync).
 * MANUAL   – entered manually by a studio user via the finance API.
 * PROVIDER – imported automatically from the external invoicing provider
 *            (e.g. invoice added by accounting directly in inFakt).
 */
enum class DocumentSource(val displayName: String) {
    VISIT("Wizyta"),
    KSEF("KSeF"),
    MANUAL("Ręcznie"),
    PROVIDER("Dostawca")
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

    /** How this document entered the system. */
    val source: DocumentSource,

    /** Optional link to the visit this document was issued for. */
    val visitId: VisitId?,

    // ── Denormalised visit / vehicle context ────────────────────────────────
    /** Vehicle brand snapshot at the time of the visit (e.g. "Toyota"). */
    val vehicleBrand: String?,

    /** Vehicle model snapshot at the time of the visit (e.g. "Corolla"). */
    val vehicleModel: String?,

    /** First name of the customer at the time of document creation. */
    val customerFirstName: String?,

    /** Last name of the customer at the time of document creation. */
    val customerLastName: String?,

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

    // ── External provider integration ──────────────────────────────────────
    /** External invoicing provider (e.g. INFAKT). Null for non-provider documents. */
    val provider: InvoiceProviderType? = null,

    /** Provider's own invoice identifier. Null until successfully sent to provider. */
    val externalId: String? = null,

    /** Human-readable invoice number assigned by the provider (e.g. "FV/2024/01/001"). */
    val externalNumber: String? = null,

    /** Status as reported by the external provider. Null for non-provider documents. */
    val externalStatus: ExternalInvoiceStatus? = null,

    /** True if this invoice is a correction (credit note) for another invoice. */
    val isCorrection: Boolean = false,

    /** True if a correction invoice has been issued for this document. */
    val hasCorrection: Boolean = false,

    /** Provider ID of the correction invoice, if one was issued for this document. */
    val correctionExternalId: String? = null,

    /**
     * Synchronization state with the external provider.
     * SYNCED      – provider confirmed the invoice; [externalId] is set.
     * SYNC_FAILED – provider call failed; can be retried via POST /finance/invoices/{id}/retry-sync.
     * Null for documents not linked to any provider.
     */
    val providerSyncStatus: InvoiceProviderSyncStatus? = null,

    /** Human-readable error from the last failed provider sync attempt. */
    val providerSyncError: String? = null,

    /** Timestamp of the last provider sync attempt (success or failure). */
    val providerSyncAttemptedAt: Instant? = null,

    /** Last time data was pulled from provider's API. Null for not-yet-synced invoices. */
    val syncedAt: Instant? = null,

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
