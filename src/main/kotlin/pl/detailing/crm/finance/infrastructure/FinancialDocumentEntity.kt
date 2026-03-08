package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
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

/**
 * JPA entity for [FinancialDocument].
 *
 * All monetary values are stored in grosz (Long, 1/100 PLN) – never as Double –
 * to avoid floating-point rounding issues in financial calculations.
 *
 * Soft-deleted documents (deletedAt IS NOT NULL) are excluded from all business
 * queries; they remain in the database for audit/compliance purposes.
 *
 * Multi-tenancy: every query MUST include a studioId predicate.
 */
@Entity
@Table(
    name = "financial_documents",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_fin_docs_studio_provider_external_id",
            columnNames = ["studio_id", "provider", "external_id"]
        )
    ],
    indexes = [
        Index(name = "idx_fin_docs_studio_id",       columnList = "studio_id"),
        Index(name = "idx_fin_docs_studio_status",   columnList = "studio_id, status"),
        Index(name = "idx_fin_docs_studio_type",     columnList = "studio_id, document_type"),
        Index(name = "idx_fin_docs_studio_dir",      columnList = "studio_id, direction"),
        Index(name = "idx_fin_docs_studio_source",   columnList = "studio_id, source"),
        Index(name = "idx_fin_docs_visit_id",        columnList = "visit_id"),
        Index(name = "idx_fin_docs_issue_date",      columnList = "studio_id, issue_date"),
        Index(name = "idx_fin_docs_due_date",        columnList = "studio_id, due_date"),
        Index(name = "idx_fin_docs_provider",        columnList = "studio_id, provider"),
        Index(name = "idx_fin_docs_external_id",     columnList = "external_id"),
        Index(name = "idx_fin_docs_sync_status",     columnList = "studio_id, provider_sync_status")
    ]
)
class FinancialDocumentEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID,

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    /**
     * How this document entered the system.
     * Column definition includes a DEFAULT so that rows created before this
     * column was added read as MANUAL when Hibernate runs ddl-auto=update.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'MANUAL'")
    val source: DocumentSource = DocumentSource.MANUAL,

    /** Optional reference to the visit this document was issued for. */
    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID?,

    // ── Denormalised vehicle / customer context ──────────────────────────
    /** Vehicle brand snapshot captured from the associated visit. */
    @Column(name = "vehicle_brand", length = 100)
    val vehicleBrand: String?,

    /** Vehicle model snapshot captured from the associated visit. */
    @Column(name = "vehicle_model", length = 100)
    val vehicleModel: String?,

    /** Customer first name at the time of document creation. */
    @Column(name = "customer_first_name", length = 100)
    val customerFirstName: String?,

    /** Customer last name at the time of document creation. */
    @Column(name = "customer_last_name", length = 100)
    val customerLastName: String?,

    @Column(name = "document_number", nullable = false, length = 50)
    val documentNumber: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 20)
    val documentType: DocumentType,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    val direction: DocumentDirection,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: DocumentStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    val paymentMethod: PaymentMethod,

    /** Net amount in grosz (1/100 PLN). */
    @Column(name = "total_net", nullable = false)
    val totalNet: Long,

    /** VAT amount in grosz. */
    @Column(name = "total_vat", nullable = false)
    val totalVat: Long,

    /** Gross amount in grosz. Invariant: totalNet + totalVat == totalGross. */
    @Column(name = "total_gross", nullable = false)
    val totalGross: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "PLN",

    @Column(name = "issue_date", nullable = false)
    val issueDate: LocalDate,

    /** Payment due date; null for CASH and CARD (settled immediately). */
    @Column(name = "due_date")
    val dueDate: LocalDate?,

    /** Timestamp of settlement; null while PENDING or OVERDUE. */
    @Column(name = "paid_at")
    var paidAt: Instant?,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    /** Name of the buyer (INCOME direction) or seller (EXPENSE direction). */
    @Column(name = "counterparty_name", length = 255)
    val counterpartyName: String?,

    /** NIP (Polish tax ID) of the counterparty. */
    @Column(name = "counterparty_nip", length = 20)
    val counterpartyNip: String?,

    // ── External provider integration ──────────────────────────────────────
    /**
     * External invoicing provider (e.g. INFAKT). Null for non-provider documents.
     * Part of the deduplication constraint: (studio_id, provider, external_id) must be unique.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", length = 20)
    var provider: InvoiceProviderType? = null,

    /**
     * Provider's own invoice identifier. Null until the invoice has been successfully
     * sent to the provider (or when the provider call failed – see [providerSyncStatus]).
     */
    @Column(name = "external_id", length = 100)
    var externalId: String? = null,

    /** Human-readable invoice number assigned by the provider (e.g. "FV/2024/01/001"). */
    @Column(name = "external_number", length = 100)
    var externalNumber: String? = null,

    /** Status as reported by the external provider. Updated during sync. */
    @Enumerated(EnumType.STRING)
    @Column(name = "external_status", length = 30)
    var externalStatus: ExternalInvoiceStatus? = null,

    /** True if this invoice is a correction (credit note) for another invoice. */
    @Column(name = "is_correction", nullable = false)
    val isCorrection: Boolean = false,

    /** True if a correction invoice has been issued for this document. Updated during sync. */
    @Column(name = "has_correction", nullable = false)
    var hasCorrection: Boolean = false,

    /** Provider ID of the correction invoice, if one was issued for this document. */
    @Column(name = "correction_external_id", length = 100)
    var correctionExternalId: String? = null,

    /**
     * Synchronization state with the external provider.
     * SYNCED      – provider confirmed the invoice; [externalId] is set.
     * SYNC_FAILED – provider call failed; can be retried via POST /finance/invoices/{id}/retry-sync.
     * Null for documents not linked to any provider.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_sync_status", length = 20)
    var providerSyncStatus: InvoiceProviderSyncStatus? = null,

    /** Human-readable error from the last failed provider sync attempt. */
    @Column(name = "provider_sync_error", columnDefinition = "TEXT")
    var providerSyncError: String? = null,

    /** Timestamp of the last provider sync attempt (success or failure). */
    @Column(name = "provider_sync_attempted_at")
    var providerSyncAttemptedAt: Instant? = null,

    /** Last time data was successfully pulled from provider's API. */
    @Column(name = "synced_at")
    var syncedAt: Instant? = null,

    // ── KSeF placeholders ─────────────────────────────────────────────────
    @Column(name = "ksef_invoice_id", columnDefinition = "uuid")
    val ksefInvoiceId: UUID?,

    @Column(name = "ksef_number", length = 100)
    val ksefNumber: String?,

    @Column(name = "created_by", columnDefinition = "uuid", nullable = false)
    val createdBy: UUID,

    @Column(name = "updated_by", columnDefinition = "uuid", nullable = false)
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    /** Soft-delete timestamp; null = document is active. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

) {
    fun toDomain(): FinancialDocument = FinancialDocument(
        id                      = FinancialDocumentId(id),
        studioId                = StudioId(studioId),
        source                  = source,
        visitId                 = visitId?.let { VisitId(it) },
        vehicleBrand            = vehicleBrand,
        vehicleModel            = vehicleModel,
        customerFirstName       = customerFirstName,
        customerLastName        = customerLastName,
        documentNumber          = documentNumber,
        documentType            = documentType,
        direction               = direction,
        status                  = status,
        paymentMethod           = paymentMethod,
        totalNet                = Money(totalNet),
        totalVat                = Money(totalVat),
        totalGross              = Money(totalGross),
        currency                = currency,
        issueDate               = issueDate,
        dueDate                 = dueDate,
        paidAt                  = paidAt,
        description             = description,
        counterpartyName        = counterpartyName,
        counterpartyNip         = counterpartyNip,
        provider                = provider,
        externalId              = externalId,
        externalNumber          = externalNumber,
        externalStatus          = externalStatus,
        isCorrection            = isCorrection,
        hasCorrection           = hasCorrection,
        correctionExternalId    = correctionExternalId,
        providerSyncStatus      = providerSyncStatus,
        providerSyncError       = providerSyncError,
        providerSyncAttemptedAt = providerSyncAttemptedAt,
        syncedAt                = syncedAt,
        ksefInvoiceId           = ksefInvoiceId,
        ksefNumber              = ksefNumber,
        createdBy               = UserId(createdBy),
        updatedBy               = UserId(updatedBy),
        createdAt               = createdAt,
        updatedAt               = updatedAt
    )
}
