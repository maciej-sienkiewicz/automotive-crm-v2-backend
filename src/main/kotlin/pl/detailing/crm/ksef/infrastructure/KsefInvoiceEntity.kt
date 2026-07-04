package pl.detailing.crm.ksef.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.ksef.domain.PaymentForm
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Unified expense document — covers both KSeF-synced and manually entered invoices.
 *
 * source        – KSEF (auto-synced) | MANUAL (entered by admin)
 * direction     – always EXPENSE (this table is expense-only)
 * isCorrection  – true when invoiceType == FA_KOR (KSeF invoices only)
 * status        – ACTIVE | CORRECTED | CANCELLED | EXCLUDED
 *                 EXCLUDED: admin marked as non-business (e.g. private purchase);
 *                           stays in DB for KSeF sync integrity, hidden from stats
 * paymentStatus – PAID | PENDING (admin-controlled)
 */
@Entity
@Table(
    name = "ksef_invoices",
    uniqueConstraints = [UniqueConstraint(columnNames = ["studio_id", "ksef_number"])],
    indexes = [
        Index(name = "idx_ksef_invoices_studio_id",        columnList = "studio_id"),
        Index(name = "idx_ksef_invoices_studio_source",    columnList = "studio_id, source"),
        Index(name = "idx_ksef_invoices_invoicing_date",   columnList = "invoicing_date"),
        Index(name = "idx_ksef_invoices_studio_status",    columnList = "studio_id, status"),
        Index(name = "idx_ksef_invoices_studio_payment",   columnList = "studio_id, payment_status")
    ]
)
class KsefInvoiceEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    /**
     * KSEF: auto-synced from the KSeF API (default).
     * MANUAL: entered manually by the admin when no KSeF invoice was received.
     * For MANUAL invoices [ksefNumber] is a locally generated placeholder.
     */
    @Column(name = "source", nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'KSEF'")
    val source: String = "KSEF",

    /** KSeF reference number (globally unique in MF system). For MANUAL entries: generated UUID placeholder. */
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

    /** KSeF invoice type (e.g. FA, FA_KOR). Null for MANUAL entries. */
    @Column(name = "invoice_type", length = 50)
    val invoiceType: String?,

    @Column(name = "fetched_at", nullable = false)
    val fetchedAt: Instant = Instant.now(),

    @Column(name = "direction", nullable = false, length = 10)
    val direction: String = "EXPENSE",

    /** True when invoiceType == FA_KOR (KSeF correction invoice). */
    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean = false,

    @Column(name = "original_ksef_number", length = 100)
    val originalKsefNumber: String? = null,

    /**
     * ACTIVE    – default
     * CORRECTED – a correction (FA_KOR) exists for this invoice
     * CANCELLED – cancelled in KSeF
     * EXCLUDED  – admin marked as non-business; hidden from stats and listings
     */
    @Column(name = "status", nullable = false, length = 20)
    val status: String = "ACTIVE",

    /**
     * PAID    – admin confirmed payment
     * PENDING – payment not yet confirmed (default)
     */
    @Column(name = "payment_status", nullable = false, length = 10, columnDefinition = "VARCHAR(10) DEFAULT 'PENDING'")
    val paymentStatus: String = "PENDING",

    /** Payment method from KSeF XML or manually specified. Stored as PaymentForm.name. */
    @Column(name = "payment_form", length = 20)
    val paymentForm: String? = null,

    /** Free-text note added by the admin (e.g. context on the expense). Null when no note set. */
    @Column(name = "note", columnDefinition = "TEXT")
    val note: String? = null

) {
    fun withStatus(newStatus: String) = copy(status = newStatus)
    fun withPaymentStatus(newPaymentStatus: String) = copy(paymentStatus = newPaymentStatus)
    fun withNote(newNote: String?) = copy(note = newNote)

    private fun copy(
        source: String = this.source,
        ksefNumber: String = this.ksefNumber,
        invoiceNumber: String? = this.invoiceNumber,
        invoicingDate: OffsetDateTime? = this.invoicingDate,
        issueDate: LocalDate? = this.issueDate,
        sellerNip: String? = this.sellerNip,
        sellerName: String? = this.sellerName,
        buyerNip: String? = this.buyerNip,
        buyerName: String? = this.buyerName,
        netAmount: Double? = this.netAmount,
        grossAmount: Double? = this.grossAmount,
        vatAmount: Double? = this.vatAmount,
        currency: String? = this.currency,
        invoiceType: String? = this.invoiceType,
        fetchedAt: Instant = this.fetchedAt,
        direction: String = this.direction,
        isCorrection: Boolean = this.isCorrection,
        originalKsefNumber: String? = this.originalKsefNumber,
        status: String = this.status,
        paymentStatus: String = this.paymentStatus,
        paymentForm: String? = this.paymentForm,
        note: String? = this.note
    ) = KsefInvoiceEntity(
        id = id, studioId = studioId,
        source = source, ksefNumber = ksefNumber, invoiceNumber = invoiceNumber,
        invoicingDate = invoicingDate, issueDate = issueDate,
        sellerNip = sellerNip, sellerName = sellerName,
        buyerNip = buyerNip, buyerName = buyerName,
        netAmount = netAmount, grossAmount = grossAmount, vatAmount = vatAmount,
        currency = currency, invoiceType = invoiceType, fetchedAt = fetchedAt,
        direction = direction, isCorrection = isCorrection,
        originalKsefNumber = originalKsefNumber, status = status,
        paymentStatus = paymentStatus, paymentForm = paymentForm, note = note
    )
}
