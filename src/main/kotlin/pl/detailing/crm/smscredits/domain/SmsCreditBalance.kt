package pl.detailing.crm.smscredits.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class SmsCreditBalance(
    val id: UUID,
    val studioId: StudioId,
    val availableCredits: Int,
    val totalPurchased: Int,
    val totalUsed: Int,
    val updatedAt: Instant
) {
    fun hasCredits(): Boolean = availableCredits > 0

    fun afterDeduction(): SmsCreditBalance = copy(
        availableCredits = availableCredits - 1,
        totalUsed = totalUsed + 1,
        updatedAt = Instant.now()
    )

    fun afterRefund(): SmsCreditBalance = copy(
        availableCredits = availableCredits + 1,
        totalUsed = (totalUsed - 1).coerceAtLeast(0),
        updatedAt = Instant.now()
    )

    fun afterPurchase(amount: Int): SmsCreditBalance = copy(
        availableCredits = availableCredits + amount,
        totalPurchased = totalPurchased + amount,
        updatedAt = Instant.now()
    )

    companion object {
        fun empty(studioId: StudioId): SmsCreditBalance = SmsCreditBalance(
            id = UUID.randomUUID(),
            studioId = studioId,
            availableCredits = 0,
            totalPurchased = 0,
            totalUsed = 0,
            updatedAt = Instant.now()
        )
    }
}
