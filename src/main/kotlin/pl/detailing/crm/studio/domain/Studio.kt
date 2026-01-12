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
    val createdAt: Instant
) {
    fun isTrialActive(): Boolean {
        return subscriptionStatus == SubscriptionStatus.TRIALING &&
                trialEndsAt != null &&
                trialEndsAt.isAfter(Instant.now())
    }

    fun isAccessible(): Boolean {
        return when (subscriptionStatus) {
            SubscriptionStatus.TRIALING -> isTrialActive()
            SubscriptionStatus.ACTIVE -> true
            SubscriptionStatus.PAST_DUE -> true // Grace period
            SubscriptionStatus.EXPIRED -> false
        }
    }

    fun getDaysRemaining(): Long? {
        if (subscriptionStatus != SubscriptionStatus.TRIALING || trialEndsAt == null) {
            return null
        }
        val now = Instant.now()
        return if (trialEndsAt.isAfter(now)) {
            java.time.Duration.between(now, trialEndsAt).toDays()
        } else {
            0L
        }
    }
}