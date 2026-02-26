package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.finance.domain.CashOperation
import pl.detailing.crm.finance.domain.CashOperationType
import pl.detailing.crm.shared.CashOperationId
import pl.detailing.crm.shared.CashRegisterId
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * JPA entity for an immutable cash-register operation (append-only audit log).
 *
 * Rows are never updated or deleted; they form the complete history of all
 * cash movements for a studio.  The current register balance equals
 * SUM(amount) over all operations for that cash register.
 *
 * [amount] is a signed Long in grosz:
 *   positive = money added (PAYMENT_IN or positive MANUAL_ADJUSTMENT)
 *   negative = money removed (PAYMENT_OUT or negative MANUAL_ADJUSTMENT)
 */
@Entity
@Table(
    name = "cash_operations",
    indexes = [
        Index(name = "idx_cash_ops_studio_id",     columnList = "studio_id"),
        Index(name = "idx_cash_ops_register_id",   columnList = "cash_register_id"),
        Index(name = "idx_cash_ops_created_at",    columnList = "studio_id, created_at"),
        Index(name = "idx_cash_ops_document_id",   columnList = "financial_document_id")
    ]
)
class CashOperationEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid", nullable = false)
    val id: UUID,

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    @Column(name = "cash_register_id", columnDefinition = "uuid", nullable = false)
    val cashRegisterId: UUID,

    /**
     * Signed change in grosz.
     * Positive → money added to the register.
     * Negative → money removed from the register.
     */
    @Column(name = "amount", nullable = false)
    val amount: Long,

    /** Balance before this operation, in grosz. */
    @Column(name = "balance_before", nullable = false)
    val balanceBefore: Long,

    /** Balance after this operation, in grosz. */
    @Column(name = "balance_after", nullable = false)
    val balanceAfter: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 30)
    val operationType: CashOperationType,

    /**
     * Reason for this operation.
     * Mandatory for [CashOperationType.MANUAL_ADJUSTMENT].
     */
    @Column(name = "comment", length = 500)
    val comment: String?,

    /** FK to the financial document that triggered this operation; null for manual entries. */
    @Column(name = "financial_document_id", columnDefinition = "uuid")
    val financialDocumentId: UUID?,

    @Column(name = "created_by", columnDefinition = "uuid", nullable = false)
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()

) {
    fun toDomain(): CashOperation = CashOperation(
        id                  = CashOperationId(id),
        studioId            = StudioId(studioId),
        cashRegisterId      = CashRegisterId(cashRegisterId),
        amount              = amount,
        balanceBefore       = Money(balanceBefore),
        balanceAfter        = Money(balanceAfter),
        operationType       = operationType,
        comment             = comment,
        financialDocumentId = financialDocumentId?.let { FinancialDocumentId(it) },
        createdBy           = UserId(createdBy),
        createdAt           = createdAt
    )
}
