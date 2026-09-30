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
    val firstName: String,
    val lastName: String,
    val studioId: String,
    val email: String,
    val phoneNumber: String,
    val role: String,
    val subscriptionStatus: SubscriptionStatus,
    val daysRemaining: Long?,
    val subscriptionEndsAt: String?,
    val trialEndsAt: String?,
    val mobileToken: String? = null,
    /** Null means full access (owner). Non-null list contains the user's effective permission codes. */
    val permissions: List<String>? = null,
    /** True when the user's role has "track work time" enabled — shows the Czas pracy sidebar entry. */
    val trackWorkTime: Boolean = false,
    /** Seconds of inactivity before the client-side lock screen fires. 0 = disabled. */
    val idleTimeoutSeconds: Int = 0,
    /**
     * Rekord pracownika powiązany z kontem albo null. Front pokazuje pozycję „Urlop"
     * (wnioski urlopowe w samoobsłudze) tylko wtedy, gdy nie jest null.
     */
    val employeeId: String? = null
)