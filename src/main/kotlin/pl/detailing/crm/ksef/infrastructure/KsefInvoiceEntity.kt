package pl.detailing.crm.ksef.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.ksef.domain.KsefInvoice
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Persists invoice metadata fetched from KSeF.
 * The ksefNumber is the unique KSeF system identifier for each invoice.
 */
@Entity
@Table(
    name = "ksef_invoices",
    uniqueConstraints = [UniqueConstraint(columnNames = ["studio_id", "ksef_number"])],
    indexes = [Index(name = "idx_ksef_invoices_studio_id", columnList = "studio_id")]
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

    @Column(name = "buyer_nip", length = 20)
    val buyerNip: String?,

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
    val fetchedAt: Instant = Instant.now()
) {
    fun toDomain(): KsefInvoice = KsefInvoice(
        id = id,
        studioId = StudioId(studioId),
        ksefNumber = ksefNumber,
        invoiceNumber = invoiceNumber,
        invoicingDate = invoicingDate,
        issueDate = issueDate,
        sellerNip = sellerNip,
        buyerNip = buyerNip,
        netAmount = netAmount,
        grossAmount = grossAmount,
        vatAmount = vatAmount,
        currency = currency,
        invoiceType = invoiceType,
        fetchedAt = fetchedAt
    )
}
