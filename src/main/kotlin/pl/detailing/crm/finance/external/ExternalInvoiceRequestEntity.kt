package pl.detailing.crm.finance.external

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Co księgowość ma wystawić. */
enum class ExternalInvoiceKind(val displayName: String) {
    /** Faktura za sprzedaż — dokument w CRM jest tylko zapisem płatności. */
    INVOICE("Faktura"),

    /** Faktura do paragonu wydanego klientowi. */
    INVOICE_TO_RECEIPT("Faktura do paragonu"),

    /** Korekta faktury, którą księgowość już wystawiła, a która przestała obowiązywać. */
    CORRECTION("Korekta faktury")
}

enum class ExternalInvoiceStatus(val displayName: String) {
    PENDING("Do wystawienia"),
    ISSUED("Wystawiona"),

    /** Nieaktualne, zanim księgowość je wystawiła — np. po zmianie faktury na paragon. */
    WITHDRAWN("Wycofane")
}

/** Dane nabywcy, które księgowość przepisze na fakturę. */
data class ExternalInvoiceBuyer(
    val nip: String? = null,
    val name: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val email: String? = null
) {
    val normalizedNip: String? get() = nip?.replace(Regex("[^0-9]"), "")?.ifBlank { null }

    val isEmpty: Boolean get() = normalizedNip == null && name.isNullOrBlank()
}

/**
 * Zgłoszenie dla księgowości: sprzedaż, do której fakturę wystawia ktoś poza CRM
 * (tryb „Faktury wystawia księgowość", V167).
 *
 * Kwoty są kopią z chwili zgłoszenia i nie idą za dokumentem: korekta zgłasza kwoty
 * ujemne faktury, którą koryguje. Nic tu nie wiąże się samo z fakturą pobraną z KSeF —
 * „Faktura wystawiona" odhacza człowiek, numer jest opcjonalny i służy tylko do
 * odszukania faktury. Biznes odrzucił automatyczne łączenie: przy tej samej kwocie
 * w tym samym tygodniu łatwo połączyć nie te dokumenty.
 */
@Entity
@Table(name = "external_invoice_requests")
class ExternalInvoiceRequestEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID?,

    /**
     * Dokument w CRM, którego dotyczy zgłoszenie. Przy zmianie samej formy płatności
     * zgłoszenie przechodzi na nowy dokument — faktura księgowości się nie zmienia.
     */
    @Column(name = "financial_document_id", columnDefinition = "uuid", nullable = false)
    var financialDocumentId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    val kind: ExternalInvoiceKind,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ExternalInvoiceStatus = ExternalInvoiceStatus.PENDING,

    /** Tylko [ExternalInvoiceKind.CORRECTION]: zgłoszenie, którego fakturę trzeba skorygować. */
    @Column(name = "corrects_request_id", columnDefinition = "uuid")
    val correctsRequestId: UUID? = null,

    @Column(name = "buyer_nip", length = 20)
    val buyerNip: String?,

    @Column(name = "buyer_name", length = 255)
    val buyerName: String?,

    @Column(name = "buyer_address_line1", length = 255)
    val buyerAddressLine1: String?,

    @Column(name = "buyer_address_line2", length = 255)
    val buyerAddressLine2: String?,

    @Column(name = "buyer_email", length = 255)
    val buyerEmail: String?,

    @Column(name = "total_net", nullable = false)
    val totalNet: Long,

    @Column(name = "total_vat", nullable = false)
    val totalVat: Long,

    @Column(name = "total_gross", nullable = false)
    val totalGross: Long,

    @Column(name = "external_invoice_number", length = 100)
    var externalInvoiceNumber: String? = null,

    @Column(name = "issued_at")
    var issuedAt: Instant? = null,

    @Column(name = "issued_by", columnDefinition = "uuid")
    var issuedBy: UUID? = null,

    @Column(name = "issued_by_name", length = 255)
    var issuedByName: String? = null,

    @Column(name = "withdrawn_at")
    var withdrawnAt: Instant? = null,

    /** Poprawka rozliczenia, która zgłoszenie utworzyła, wycofała albo przeniosła. */
    @Column(name = "settlement_correction_id", columnDefinition = "uuid")
    var settlementCorrectionId: UUID? = null,

    @Column(name = "created_by", columnDefinition = "uuid", nullable = false)
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    /** Zgłoszenie, które wciąż coś znaczy: czeka na księgowość albo opisuje wystawioną fakturę. */
    val isActive: Boolean get() = status != ExternalInvoiceStatus.WITHDRAWN

    val buyer: ExternalInvoiceBuyer
        get() = ExternalInvoiceBuyer(buyerNip, buyerName, buyerAddressLine1, buyerAddressLine2, buyerEmail)
}
