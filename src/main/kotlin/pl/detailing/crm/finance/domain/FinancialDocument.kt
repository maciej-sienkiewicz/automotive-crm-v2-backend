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
    OTHER("DOK",   "Dokument"),
    /**
     * Storno dokumentu poprawianego w rozliczeniu wizyty (albo odbicie faktury korygującej
     * KSeF). Kwoty ze znakiem - zwykle ujemne - więc każda suma po dokumentach liczy się
     * poprawnie bez warunków na typ: paragon + jego korekta = 0.
     */
    CORRECTION("KOR", "Korekta")
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
    BLIK_NA_NUMER("BLIK na numer"),
    BLIK_TERMINAL("BLIK terminal"),
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
    /** Grosze ze znakiem: dokument [DocumentType.CORRECTION] ma zwykle kwoty ujemne. */
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
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
    val deletedAt: Instant? = null,
    /** Faktura KSeF, której adnotacją jest ten dokument; null dla paragonu i dokumentu „inny". */
    val ksefRevenueInvoiceId: java.util.UUID? = null,
    /** Dokument, który ta korekta storno koryguje; tylko dla [DocumentType.CORRECTION]. */
    val correctsDocumentId: java.util.UUID? = null,
    /** Dokument zastąpiony w poprawce rozliczenia — zostaje w historii, obok stoi jego korekta. */
    val supersededAt: Instant? = null
) {
    init {
        require(totalNet + totalVat == totalGross) {
            "Financial integrity: totalNet + totalVat ≠ totalGross"
        }
    }
}
