package pl.detailing.crm.auth

import pl.detailing.crm.shared.SubscriptionStatus

data class UnifiedAuthResponse(
    val success: Boolean,
    val message: String? = null,
    val redirectUrl: String? = null,
    val user: UserData? = null
)

data class UserData(
    val userId: String,
    val studioId: String,
    val email: String,
    val role: String,
    val subscriptionStatus: SubscriptionStatus,
    val trialDaysRemaining: Long?
)