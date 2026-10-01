package pl.detailing.crm.studio.domain

import pl.detailing.crm.shared.*
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import pl.detailing.crm.subscription.lifecycle.BillingSnapshot
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
    val emailAlias: String?,
    /** Rodzaj studia - nadawany przy zakładaniu, nigdy nie zmieniany (patrz [StudioKind]). */
    val kind: StudioKind = StudioKind.REGULAR,
    /** Koniec karencji (PAST_DUE); null w pozostałych stanach. */
    val graceEndsAt: Instant? = null
) {
    /**
     * Stan rozliczeniowy. O dostępie decyduje
     * [pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy] — dawne
     * `isAccessible()`/`isSubscriptionActive()` liczyły to tutaj z `Instant.now()`, z inną
     * granicą czasu niż scheduler i z `PAST_DUE` otwartym bez końca (audyt, S5, S8).
     */
    fun billing(): BillingSnapshot = BillingSnapshot(
        status = subscriptionStatus,
        trialEndsAt = trialEndsAt,
        subscriptionEndsAt = subscriptionEndsAt,
        graceEndsAt = graceEndsAt
    )
}
