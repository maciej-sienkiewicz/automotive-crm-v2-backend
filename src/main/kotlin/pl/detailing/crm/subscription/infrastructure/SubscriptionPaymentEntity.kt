package pl.detailing.crm.subscription.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.domain.SubscriptionPayment
import pl.detailing.crm.subscription.domain.SubscriptionPlanType
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "subscription_payments",
    indexes = [Index(name = "idx_subscription_payments_studio_id", columnList = "studio_id, created_at")]
)
class SubscriptionPaymentEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_type", nullable = false, length = 20)
    val planType: SubscriptionPlanType,

    @Column(name = "duration_days", nullable = false)
    val durationDays: Int,

    @Column(name = "amount_in_cents", nullable = false)
    val amountInCents: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    @Column(name = "payment_transaction_id", nullable = false, length = 255)
    val paymentTransactionId: String,

    @Column(name = "subscription_ends_at", nullable = false, columnDefinition = "timestamp with time zone")
    val subscriptionEndsAt: Instant,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): SubscriptionPayment = SubscriptionPayment(
        id = id,
        studioId = StudioId(studioId),
        planType = planType,
        durationDays = durationDays,
        amountInCents = amountInCents,
        currency = currency,
        paymentTransactionId = paymentTransactionId,
        subscriptionEndsAt = subscriptionEndsAt,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(domain: SubscriptionPayment): SubscriptionPaymentEntity = SubscriptionPaymentEntity(
            id = domain.id,
            studioId = domain.studioId.value,
            planType = domain.planType,
            durationDays = domain.durationDays,
            amountInCents = domain.amountInCents,
            currency = domain.currency,
            paymentTransactionId = domain.paymentTransactionId,
            subscriptionEndsAt = domain.subscriptionEndsAt,
            createdAt = domain.createdAt
        )
    }
}
