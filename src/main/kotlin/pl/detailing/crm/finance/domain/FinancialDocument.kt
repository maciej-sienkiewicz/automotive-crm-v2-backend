package pl.detailing.crm.finance.domain

import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate

// ── Domain Enums ───────────────────────────────────────────────────────────────

enum class DocumentType(val prefix: String, val displayName: String) {
    RECEIPT("PAR", "Paragon"),
    INVOICE("FAK", "Faktura"),
    OTHER("DOK",   "Dokument")
}

enum class DocumentDirection(val displayName: String) {
    INCOME("Przychód"),
    EXPENSE("Koszt")
}

enum class DocumentStatus(val displayName: String) {
    PAID("Opłacony"),
    PENDING("Oczekujący"),
    OVERDUE("Przeterminowany")
}

enum class PaymentMethod(val displayName: String) {
    CASH("Gotówka"),
    CARD("Karta"),
    TRANSFER("Przelew"),
    OTHER("Inne");

    fun defaultStatus(): DocumentStatus = when (this) {
        TRANSFER -> DocumentStatus.PENDING
        else     -> DocumentStatus.PAID
    }

    fun affectsCashRegister(): Boolean = this == CASH
}

enum class DocumentSource(val displayName: String) {
    VISIT("Wizyta"),
    MANUAL("Ręcznie")
}

// ── Domain Model ───────────────────────────────────────────────────────────────

/**
 * Income record (Dokument Przychodowy) — tracks that a visit generated an external invoice.
 * This is NOT a formal invoice; it is a revenue-tracking record in the CRM.
 *
 * All monetary amounts in grosz (1/100 PLN). Invariant: totalNet + totalVat == totalGross.
 */
data class FinancialDocument(
    val id: FinancialDocumentId,
    val studioId: StudioId,
    val source: DocumentSource,
    val visitId: VisitId?,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val customerFirstName: String?,
    val customerLastName: String?,
    val documentNumber: String,
    val documentType: DocumentType,
    val direction: DocumentDirection,
    val status: DocumentStatus,
    val paymentMethod: PaymentMethod,
    val totalNet: Money,
    val totalVat: Money,
    val totalGross: Money,
    val currency: String,
    val issueDate: LocalDate,
    val dueDate: LocalDate?,
    val paidAt: Instant?,
    val description: String?,
    val counterpartyName: String?,
    val counterpartyNip: String?,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null
) {
    init {
        require(totalNet.amountInCents + totalVat.amountInCents == totalGross.amountInCents) {
            "Financial integrity: totalNet + totalVat ≠ totalGross"
        }
    }
}
