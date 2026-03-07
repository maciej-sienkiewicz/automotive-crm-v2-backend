package pl.detailing.crm.invoicing.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.invoicing.domain.ExternalInvoice
import pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.shared.ExternalInvoiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Local record of an invoice issued via an external provider.
 *
 * Serves as a local cache so we can list invoices without calling the provider API
 * on every request, and to track correction status across sync cycles.
 *
 * Monetary values are stored in grosz (Long, 1/100 PLN).
 * Unique constraint: (studio_id, provider, external_id) – an invoice from a given
 * provider is globally unique within a studio.
 */
@Entity
@Table(
    name = "external_invoices",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_external_invoices_studio_provider_id",
            columnNames = ["studio_id", "provider", "external_id"]
        )
    ],
    indexes = [
        Index(name = "idx_ext_inv_studio_id",       columnList = "studio_id"),
        Index(name = "idx_ext_inv_studio_provider",  columnList = "studio_id, provider"),
        Index(name = "idx_ext_inv_studio_status",    columnList = "studio_id, status"),
        Index(name = "idx_ext_inv_issue_date",       columnList = "studio_id, issue_date"),
        Index(name = "idx_ext_inv_external_id",      columnList = "external_id")
    ]
)
class ExternalInvoiceEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    val provider: InvoiceProviderType,

    /** Provider's own identifier for this invoice. */
    @Column(name = "external_id", nullable = false, length = 100)
    val externalId: String,

    /** Human-readable invoice number from the provider (e.g. "FV/2024/01/001"). */
    @Column(name = "external_number", length = 100)
    val externalNumber: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: ExternalInvoiceStatus,

    /** True if this invoice is a correction (credit note) for another invoice. */
    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean = false,

    /** True if another invoice has been issued correcting this one. */
    @Column(name = "has_correction", nullable = false)
    var hasCorrection: Boolean = false,

    /** Provider's ID of the correction invoice issued for this invoice, if any. */
    @Column(name = "correction_external_id", length = 100)
    var correctionExternalId: String? = null,

    /** Gross amount in grosz (1/100 PLN). */
    @Column(name = "gross_amount", nullable = false)
    val grossAmount: Long,

    /** Net amount in grosz. */
    @Column(name = "net_amount", nullable = false)
    val netAmount: Long,

    /** VAT amount in grosz. Invariant: netAmount + vatAmount == grossAmount. */
    @Column(name = "vat_amount", nullable = false)
    val vatAmount: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "PLN",

    @Column(name = "issue_date", nullable = false)
    val issueDate: LocalDate,

    @Column(name = "due_date")
    val dueDate: LocalDate?,

    @Column(name = "buyer_name", length = 255)
    val buyerName: String?,

    @Column(name = "buyer_nip", length = 20)
    val buyerNip: String?,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    /** Last time data was pulled from provider's API. */
    @Column(name = "synced_at", nullable = false)
    var syncedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

) {
    fun toDomain(portalUrl: String): ExternalInvoice = ExternalInvoice(
        id                   = ExternalInvoiceId(id),
        studioId             = StudioId(studioId),
        provider             = provider,
        externalId           = externalId,
        externalNumber       = externalNumber,
        status               = status,
        isCorrection         = isCorrection,
        hasCorrection        = hasCorrection,
        correctionExternalId = correctionExternalId,
        grossAmount          = grossAmount,
        netAmount            = netAmount,
        vatAmount            = vatAmount,
        currency             = currency,
        issueDate            = issueDate,
        dueDate              = dueDate,
        buyerName            = buyerName,
        buyerNip             = buyerNip,
        description          = description,
        externalUrl          = portalUrl,
        syncedAt             = syncedAt,
        createdAt            = createdAt,
        updatedAt            = updatedAt
    )
}
