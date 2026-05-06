package pl.detailing.crm.studio.domain

import pl.detailing.crm.shared.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

// ==================== DOMAIN MODEL ====================

/**
 * Studio (Company/Tenant) - Root entity for multi-tenancy
 */
data class Studio(
    val id: StudioId,
    val name: String,
    val subscriptionStatus: SubscriptionStatus,
    val trialEndsAt: Instant?,
    val subscriptionEndsAt: Instant?,
    val trialUsed: Boolean,
    val createdAt: Instant,
    val emailAlias: String?
) {
    fun isTrialActive(): Boolean =
        subscriptionStatus == SubscriptionStatus.TRIALING &&
        trialEndsAt != null &&
        trialEndsAt.isAfter(Instant.now())

    fun isSubscriptionActive(): Boolean =
        subscriptionStatus == SubscriptionStatus.ACTIVE &&
        subscriptionEndsAt != null &&
        subscriptionEndsAt.isAfter(Instant.now())

    fun isAccessible(): Boolean = when (subscriptionStatus) {
        SubscriptionStatus.TRIALING  -> isTrialActive()
        SubscriptionStatus.ACTIVE    -> isSubscriptionActive()
        SubscriptionStatus.PAST_DUE  -> true
        SubscriptionStatus.EXPIRED   -> false
    }

    fun getDaysRemaining(): Long? {
        val expiresAt = when (subscriptionStatus) {
            SubscriptionStatus.TRIALING -> trialEndsAt
            SubscriptionStatus.ACTIVE   -> subscriptionEndsAt
            else                        -> null
        } ?: return null

        val now = Instant.now()
        return if (expiresAt.isAfter(now)) java.time.Duration.between(now, expiresAt).toDays() else 0L
    }
}