package pl.detailing.crm.finance.domain

import pl.detailing.crm.shared.CashOperationId
import pl.detailing.crm.shared.CashRegisterId
import pl.detailing.crm.shared.FinancialDocumentId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// Domain Enum
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The nature of a single cash-register operation.
 *
 * PAYMENT_IN        – automatic entry when a CASH INCOME document is created.
 * PAYMENT_OUT       – automatic entry when a CASH EXPENSE document is created.
 * MANUAL_ADJUSTMENT – manual entry (start-of-day float, bank deposit, withdrawal,
 *                     discrepancy correction, etc.); always requires a [CashOperation.comment].
 */
enum class CashOperationType(val displayName: String) {
    PAYMENT_IN("Wpłata"),
    PAYMENT_OUT("Wypłata"),
    MANUAL_ADJUSTMENT("Korekta manualna")
}

// ─────────────────────────────────────────────────────────────────────────────
// Domain Model
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable record of a single change to the studio's cash-register balance.
 *
 * Cash operations form an append-only audit log: once created they are never
 * updated or deleted. The current cash balance can always be reconstructed by
 * replaying all operations for a given [cashRegisterId].
 *
 * [amount] is a signed value in grosz:
 *   positive → money added to the register (PAYMENT_IN / positive MANUAL_ADJUSTMENT)
 *   negative → money removed from the register (PAYMENT_OUT / negative MANUAL_ADJUSTMENT)
 *
 * [comment] is mandatory for [CashOperationType.MANUAL_ADJUSTMENT] and optional for
 * automatic entries (though recommended for traceability).
 */
data class CashOperation(
    val id: CashOperationId,
    val studioId: StudioId,
    val cashRegisterId: CashRegisterId,

    /**
     * Signed change amount in grosz.
     * Positive = money in, Negative = money out.
     */
    val amount: Long,

    val balanceBefore: Money,
    val balanceAfter: Money,

    val operationType: CashOperationType,

    /**
     * Description of why this operation was performed.
     * REQUIRED for [CashOperationType.MANUAL_ADJUSTMENT];
     * optional (but encouraged) for automatic entries.
     */
    val comment: String?,

    /** Set for PAYMENT_IN / PAYMENT_OUT entries tied to a specific document. */
    val financialDocumentId: FinancialDocumentId?,

    val createdBy: UserId,
    val createdAt: Instant
)
