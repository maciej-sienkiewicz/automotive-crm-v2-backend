package pl.detailing.crm.invoicing.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.invoicing.domain.ExternalInvoice
import pl.detailing.crm.invoicing.domain.ExternalInvoiceStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.shared.ExternalInvoiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Single source of truth for all invoices in the system.
 *
 * Invoices are created locally when a visit is completed and then synchronised
 * with the configured external provider (e.g. inFakt). If no provider is configured,
 * the visit completion proceeds without issuing an invoice.
 *
 * Deduplication guarantee: the unique constraint on (studio_id, provider, external_id)
 * prevents the same provider invoice from appearing twice. NULL values in provider/external_id
 * are treated as distinct by PostgreSQL, so locally-created invoices that failed to sync
 * do not collide with each other.
 *
 * Monetary values are stored in grosz (Long, 1/100 PLN).
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
        Index(name = "idx_ext_inv_external_id",      columnList = "external_id"),
        Index(name = "idx_ext_inv_visit_id",         columnList = "visit_id"),
        Index(name = "idx_ext_inv_sync_status",      columnList = "studio_id, provider_sync_status")
    ]
)
class ExternalInvoiceEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    /**
     * External provider this invoice belongs to.
     * Null when the provider call failed and the invoice exists only locally.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    val provider: InvoiceProviderType?,

    /**
     * Provider's own identifier for this invoice.
     * Null until the invoice has been successfully sent to the provider.
     */
    @Column(name = "external_id", length = 100)
    var externalId: String?,

    /** Human-readable invoice number assigned by the provider (e.g. "FV/2024/01/001"). */
    @Column(name = "external_number", length = 100)
    var externalNumber: String?,

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

    /**
     * Optional reference to the visit this invoice was issued for.
     * Null for invoices created manually or imported from the provider without a visit context.
     */
    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID? = null,

    /**
     * Whether the invoice has been successfully synchronised with the external provider.
     * SYNCED      – provider confirmed the invoice; [externalId] is set.
     * SYNC_FAILED – provider call failed; invoice can be retried via POST /invoices/{id}/retry-sync.
     *
     * Column default 'SYNCED' ensures existing rows (created before this column was added)
     * are treated as already synced.
     */
    @Enumerated(EnumType.STRING)
    @Column(
        name = "provider_sync_status",
        nullable = false,
        length = 20,
        columnDefinition = "VARCHAR(20) DEFAULT 'SYNCED'"
    )
    var providerSyncStatus: InvoiceProviderSyncStatus = InvoiceProviderSyncStatus.SYNCED,

    /** Human-readable error from the last failed provider sync attempt. */
    @Column(name = "provider_sync_error", columnDefinition = "TEXT")
    var providerSyncError: String? = null,

    /** Timestamp of the last provider sync attempt (success or failure). */
    @Column(name = "provider_sync_attempted_at")
    var providerSyncAttemptedAt: Instant? = null,

    /** Last time data was pulled from provider's API. Null for locally-created invoices not yet synced. */
    @Column(name = "synced_at")
    var syncedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

) {
    /**
     * Maps the entity to its domain representation.
     *
     * @param portalUrl Direct URL to view this invoice on the provider's portal.
     *                  Pass null for locally-created invoices that have not yet been synced.
     */
    fun toDomain(portalUrl: String? = null): ExternalInvoice = ExternalInvoice(
        id                      = ExternalInvoiceId(id),
        studioId                = StudioId(studioId),
        provider                = provider,
        externalId              = externalId,
        externalNumber          = externalNumber,
        status                  = status,
        isCorrection            = isCorrection,
        hasCorrection           = hasCorrection,
        correctionExternalId    = correctionExternalId,
        grossAmount             = grossAmount,
        netAmount               = netAmount,
        vatAmount               = vatAmount,
        currency                = currency,
        issueDate               = issueDate,
        dueDate                 = dueDate,
        buyerName               = buyerName,
        buyerNip                = buyerNip,
        description             = description,
        visitId                 = visitId,
        providerSyncStatus      = providerSyncStatus,
        providerSyncError       = providerSyncError,
        providerSyncAttemptedAt = providerSyncAttemptedAt,
        externalUrl             = portalUrl,
        syncedAt                = syncedAt,
        createdAt               = createdAt,
        updatedAt               = updatedAt
    )
}
