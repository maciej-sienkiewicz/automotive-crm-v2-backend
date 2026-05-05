package pl.detailing.crm.subscription.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class SubscriptionPayment(
    val id: UUID,
    val studioId: StudioId,
    val planType: SubscriptionPlanType,
    val durationDays: Int,
    val amountInCents: Long,
    val currency: String,
    val paymentTransactionId: String,
    val subscriptionEndsAt: Instant,
    val createdAt: Instant
)
