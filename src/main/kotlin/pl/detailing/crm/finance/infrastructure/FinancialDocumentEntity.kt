package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.finance.domain.DocumentDirection
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
 * JPA entity for [FinancialDocument].
 *
 * All monetary values are stored in grosz (Long, 1/100 PLN) – never as Double –
 * to avoid floating-point rounding issues in financial calculations.
 *
 * Soft-deleted documents (deletedAt IS NOT NULL) are excluded from all business
 * queries; they remain in the database for audit/compliance purposes.
 *
 * Multi-tenancy: every query MUST include a studioId predicate.
 * Indexed columns: studio_id, status, document_type, visit_id, issue_date.
 */
@Entity
@Table(
    name = "financial_documents",
    indexes = [
        Index(name = "idx_fin_docs_studio_id",     columnList = "studio_id"),
        Index(name = "idx_fin_docs_studio_status",  columnList = "studio_id, status"),
        Index(name = "idx_fin_docs_studio_type",    columnList = "studio_id, document_type"),
        Index(name = "idx_fin_docs_studio_dir",     columnList = "studio_id, direction"),
        Index(name = "idx_fin_docs_visit_id",       columnList = "visit_id"),
        Index(name = "idx_fin_docs_issue_date",     columnList = "studio_id, issue_date"),
        Index(name = "idx_fin_docs_due_date",       columnList = "studio_id, due_date")
    ]
)
class FinancialDocumentEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID,

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    /** Optional reference to the visit this document was issued for. */
    @Column(name = "visit_id", columnDefinition = "uuid")
    val visitId: UUID?,

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

    // ── KSeF placeholders ─────────────────────────────────────────────────
    /** Reserved for future KSeF integration: FK to ksef_invoices.id. */
    @Column(name = "ksef_invoice_id", columnDefinition = "uuid")
    val ksefInvoiceId: UUID?,

    /** Reserved for future KSeF integration: KSeF reference number. */
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
        id                = FinancialDocumentId(id),
        studioId          = StudioId(studioId),
        visitId           = visitId?.let { VisitId(it) },
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
        ksefInvoiceId     = ksefInvoiceId,
        ksefNumber        = ksefNumber,
        createdBy         = UserId(createdBy),
        updatedBy         = UserId(updatedBy),
        createdAt         = createdAt,
        updatedAt         = updatedAt
    )

    companion object {
        fun fromDomain(doc: FinancialDocument): FinancialDocumentEntity = FinancialDocumentEntity(
            id               = doc.id.value,
            studioId         = doc.studioId.value,
            visitId          = doc.visitId?.value,
            documentNumber   = doc.documentNumber,
            documentType     = doc.documentType,
            direction        = doc.direction,
            status           = doc.status,
            paymentMethod    = doc.paymentMethod,
            totalNet         = doc.totalNet.amountInCents,
            totalVat         = doc.totalVat.amountInCents,
            totalGross       = doc.totalGross.amountInCents,
            currency         = doc.currency,
            issueDate        = doc.issueDate,
            dueDate          = doc.dueDate,
            paidAt           = doc.paidAt,
            description      = doc.description,
            counterpartyName = doc.counterpartyName,
            counterpartyNip  = doc.counterpartyNip,
            ksefInvoiceId    = doc.ksefInvoiceId,
            ksefNumber       = doc.ksefNumber,
            createdBy        = doc.createdBy.value,
            updatedBy        = doc.updatedBy.value,
            createdAt        = doc.createdAt,
            updatedAt        = doc.updatedAt
        )
    }
}
