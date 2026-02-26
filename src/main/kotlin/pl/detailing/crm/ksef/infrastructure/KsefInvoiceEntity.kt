package pl.detailing.crm.ksef.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.ksef.domain.KsefInvoice
import pl.detailing.crm.ksef.domain.PaymentForm
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persists invoice metadata fetched from KSeF.
 *
 * direction        – INCOME (studio jest sprzedawcą, SUBJECT1) lub EXPENSE (studio jest nabywcą, SUBJECT2)
 * isCorrection     – true gdy invoiceType == FA_KOR
 * originalKsefNumber – numer KSeF korygowanej faktury (null gdy niedostępny w SDK)
 * status           – ACTIVE (domyślnie) | CORRECTED (ma korektę) | CANCELLED
 *
 * Unikalne klucze: (studio_id, ksef_number) – KSeF number jest globalnie unikalny w systemie MF.
 */
@Entity
@Table(
    name = "ksef_invoices",
    uniqueConstraints = [UniqueConstraint(columnNames = ["studio_id", "ksef_number"])],
    indexes = [
        Index(name = "idx_ksef_invoices_studio_id", columnList = "studio_id"),
        Index(name = "idx_ksef_invoices_studio_direction", columnList = "studio_id, direction"),
        Index(name = "idx_ksef_invoices_invoicing_date", columnList = "invoicing_date")
    ]
)
class KsefInvoiceEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Column(name = "ksef_number", nullable = false, length = 100)
    val ksefNumber: String,

    @Column(name = "invoice_number", length = 255)
    val invoiceNumber: String?,

    @Column(name = "invoicing_date")
    val invoicingDate: OffsetDateTime?,

    @Column(name = "issue_date")
    val issueDate: LocalDate?,

    @Column(name = "seller_nip", length = 20)
    val sellerNip: String?,

    @Column(name = "seller_name", length = 500)
    val sellerName: String?,

    @Column(name = "buyer_nip", length = 20)
    val buyerNip: String?,

    @Column(name = "buyer_name", length = 500)
    val buyerName: String?,

    @Column(name = "net_amount")
    val netAmount: Double?,

    @Column(name = "gross_amount")
    val grossAmount: Double?,

    @Column(name = "vat_amount")
    val vatAmount: Double?,

    @Column(name = "currency", length = 3)
    val currency: String?,

    @Column(name = "invoice_type", length = 50)
    val invoiceType: String?,

    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: Instant = Instant.now(),

    /** INCOME = studio jest sprzedawcą (SUBJECT1), EXPENSE = studio jest nabywcą (SUBJECT2) */
    @Column(name = "direction", nullable = false, length = 10)
    val direction: String = "INCOME",

    /** true gdy invoiceType == FA_KOR */
    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean = false,

    /** Numer KSeF faktury korygowanej – dostępny tylko gdy SDK zwraca to pole w metadanych */
    @Column(name = "original_ksef_number", length = 100)
    val originalKsefNumber: String? = null,

    /** ACTIVE (domyślnie) | CORRECTED (do tej faktury wystawiono korektę) | CANCELLED */
    @Column(name = "status", nullable = false, length = 20)
    val status: String = "ACTIVE",

    /**
     * Forma płatności wyciągnięta z pełnego XML faktury KSeF (pole <FormaPlatnosci>).
     * Przechowywana jako nazwa enuma (np. "PRZELEW"), null gdy nie udało się pobrać XML.
     * Mapowanie: PaymentForm.name ↔ PaymentForm.fromName()
     */
    @Column(name = "payment_form", length = 20)
    val paymentForm: String? = null

) {
    fun toDomain(): KsefInvoice = KsefInvoice(
        id = id,
        studioId = StudioId(studioId),
        ksefNumber = ksefNumber,
        invoiceNumber = invoiceNumber,
        invoicingDate = invoicingDate,
        issueDate = issueDate,
        sellerNip = sellerNip,
        sellerName = sellerName,
        buyerNip = buyerNip,
        buyerName = buyerName,
        netAmount = netAmount,
        grossAmount = grossAmount,
        vatAmount = vatAmount,
        currency = currency,
        invoiceType = invoiceType,
        fetchedAt = fetchedAt,
        direction = direction,
        isCorrection = isCorrection,
        originalKsefNumber = originalKsefNumber,
        status = status,
        paymentForm = paymentForm?.let { runCatching { PaymentForm.valueOf(it) }.getOrNull() }
    )

    fun withStatus(newStatus: String): KsefInvoiceEntity = KsefInvoiceEntity(
        id = id, studioId = studioId, ksefNumber = ksefNumber, invoiceNumber = invoiceNumber,
        invoicingDate = invoicingDate, issueDate = issueDate, sellerNip = sellerNip,
        sellerName = sellerName, buyerNip = buyerNip, buyerName = buyerName,
        netAmount = netAmount, grossAmount = grossAmount,
        vatAmount = vatAmount, currency = currency, invoiceType = invoiceType,
        fetchedAt = fetchedAt, direction = direction, isCorrection = isCorrection,
        originalKsefNumber = originalKsefNumber, status = newStatus,
        paymentForm = paymentForm
    )
}
