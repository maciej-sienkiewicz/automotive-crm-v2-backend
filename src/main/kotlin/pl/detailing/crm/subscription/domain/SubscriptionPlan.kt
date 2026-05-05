package pl.detailing.crm.subscription.domain

import java.math.BigDecimal

enum class SubscriptionPlanType { MONTHLY, YEARLY }

data class SubscriptionPlan(
    val type: SubscriptionPlanType,
    val name: String,
    val durationDays: Int,
    val priceGrossInCents: Long,
    val currency: String
) {
    val priceGross: BigDecimal get() = BigDecimal(priceGrossInCents).movePointLeft(2)

    companion object {
        val MONTHLY = SubscriptionPlan(
            type = SubscriptionPlanType.MONTHLY,
            name = "Miesięczna",
            durationDays = 30,
            priceGrossInCents = 9900L,
            currency = "PLN"
        )
        val YEARLY = SubscriptionPlan(
            type = SubscriptionPlanType.YEARLY,
            name = "Roczna",
            durationDays = 365,
            priceGrossInCents = 99900L,
            currency = "PLN"
        )

        val ALL = listOf(MONTHLY, YEARLY)

        fun forType(type: SubscriptionPlanType): SubscriptionPlan = ALL.first { it.type == type }
    }
}
