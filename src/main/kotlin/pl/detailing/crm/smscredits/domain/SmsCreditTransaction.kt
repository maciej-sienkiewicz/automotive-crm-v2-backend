package pl.detailing.crm.smscredits.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class SmsCreditTransaction(
    val id: UUID,
    val studioId: StudioId,
    val type: SmsCreditTransactionType,
    val amount: Int,
    val balanceAfter: Int,
    val description: String,
    val referenceId: String?,
    val createdAt: Instant
)

enum class SmsCreditTransactionType {
    PURCHASE,
    DEDUCTION,
    REFUND,
    BONUS
}
