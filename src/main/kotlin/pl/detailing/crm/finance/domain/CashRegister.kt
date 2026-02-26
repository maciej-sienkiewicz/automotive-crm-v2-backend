package pl.detailing.crm.finance.domain

import pl.detailing.crm.shared.CashRegisterId
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * Represents the current state of a studio's physical cash register (kasa fiskalna).
 *
 * Each studio has exactly one cash register, created automatically on the first
 * cash-affecting operation. The [balance] reflects all cash inflows (INCOME payments
 * in CASH) and outflows (EXPENSE payments in CASH + manual adjustments).
 *
 * The full history of how the balance arrived at its current value is tracked in
 * [CashOperation] entries – see [CashOperationRepository.findByStudioId].
 */
data class CashRegister(
    val id: CashRegisterId,
    val studioId: StudioId,

    /** Current cash balance in grosz (1/100 PLN). Always ≥ 0. */
    val balance: Money,

    /** ISO-4217 currency code; PLN for all domestic registers. */
    val currency: String,

    val updatedAt: Instant
)
