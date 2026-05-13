package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Persists income records (Dokumenty Przychodowe).
 *
 * These are revenue-tracking records, not formal invoices.
 * All amounts in grosz (Long, 1/100 PLN) — never Double — to avoid floating-point issues.
 * Soft-deleted documents (deletedAt IS NOT NULL) are excluded from all business queries.
 *
 * NOTE: The underlying financial_documents table contains additional provider-integration
 * columns from a previous implementation (provider, external_id, ksef_number, etc.).
 * Those columns are no longer mapped here and will remain in the DB as orphaned columns.
 */
@Entity
@Table(
    name = "financial_documents",
    indexes = [
        Index(name = "idx_fin_docs_studio_id",     columnList = "studio_id"),
        Index(name = "idx_fin_docs_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_fin_docs_studio_type",   columnList = "studio_id, document_type"),
        Index(name = "idx_fin_docs_studio_dir",    columnList = "studio_id, direction"),
        Index(name = "idx_fin_docs_studio_source", columnList = "studio_id, source"),
        Index(name = "idx_fin_docs_visit_id",      columnList = "visit_id"),
        Index(name = "idx_fin_docs_issue_date",    columnList = "studio_id, issue_date")
    ]
)
class FinancialDocumentEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID,

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'MANUAL'")
    val source: DocumentSource = DocumentSource.MANUAL,

    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID?,

    @Column(name = "vehicle_brand", length = 100)
    val vehicleBrand: String?,

    @Column(name = "vehicle_model", length = 100)
    val vehicleModel: String?,

    @Column(name = "customer_first_name", length = 100)
    val customerFirstName: String?,

    @Column(name = "customer_last_name", length = 100)
    val customerLastName: String?,

    @Column(name = "document_number", nullable = false, length = 50)
    var documentNumber: String,

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

    @Column(name = "total_net", nullable = false)
    val totalNet: Long,

    @Column(name = "total_vat", nullable = false)
    val totalVat: Long,

    @Column(name = "total_gross", nullable = false)
    val totalGross: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "PLN",

    @Column(name = "issue_date", nullable = false)
    val issueDate: LocalDate,

    @Column(name = "due_date")
    val dueDate: LocalDate?,

    @Column(name = "paid_at")
    var paidAt: Instant?,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "counterparty_name", length = 255)
    val counterpartyName: String?,

    @Column(name = "counterparty_nip", length = 20)
    val counterpartyNip: String?,

    @Column(name = "created_by", columnDefinition = "uuid", nullable = false)
    val createdBy: UUID,

    @Column(name = "updated_by", columnDefinition = "uuid", nullable = false)
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

) {
    fun toDomain() = FinancialDocument(
        id                = FinancialDocumentId(id),
        studioId          = StudioId(studioId),
        source            = source,
        visitId           = visitId?.let { VisitId(it) },
        vehicleBrand      = vehicleBrand,
        vehicleModel      = vehicleModel,
        customerFirstName = customerFirstName,
        customerLastName  = customerLastName,
        documentNumber    = documentNumber,
        documentType      = documentType,
        direction         = direction,
        status            = status,
        paymentMethod     = paymentMethod,
        totalNet          = Money(totalNet),
        totalVat          = Money(totalVat),
        totalGross        = Money(totalGross),
        currency          = currency,
        issueDate         = issueDate,
        dueDate           = dueDate,
        paidAt            = paidAt,
        description       = description,
        counterpartyName  = counterpartyName,
        counterpartyNip   = counterpartyNip,
        createdBy         = UserId(createdBy),
        updatedBy         = UserId(updatedBy),
        createdAt         = createdAt,
        updatedAt         = updatedAt,
        deletedAt         = deletedAt
    )
}
