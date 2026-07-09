package pl.detailing.crm.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Subscription lifecycle: studio creation, trial management, status queries
 * and expiry sweeps.
 *
 * All money movement lives in the payments module ([pl.detailing.crm.payments]):
 * purchases, renewals, upgrades and module activations go through
 * CheckoutService → Przelewy24 → OrderFulfillmentService.
 */
@Service
class SubscriptionService(
    private val studioRepository: StudioRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TRIAL_DURATION_DAYS = 60L
    }

    // ─── Studio creation ──────────────────────────────────────────────────────

    /** Creates a studio with no plan — user must explicitly choose trial or paid plan. */
    suspend fun createStudio(name: String): Studio = withContext(Dispatchers.IO) {
        val studio = Studio(
            id = StudioId.random(),
            name = name,
            subscriptionStatus = SubscriptionStatus.NO_PLAN,
            trialEndsAt = null,
            subscriptionEndsAt = null,
            trialUsed = false,
            createdAt = Instant.now(),
            emailAlias = UUID.randomUUID().toString().replace("-", "")
        )
        studioRepository.save(StudioEntity.fromDomain(studio))
        studio
    }

    /** Starts the free trial for a studio that has never used one. */
    @Transactional
    suspend fun startTrial(studioId: StudioId): SubscriptionInfo = withContext(Dispatchers.IO) {
        val entity = studioRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")

        if (entity.trialUsed) throw ValidationException("Okres próbny został już wykorzystany.")
        if (entity.subscriptionStatus == SubscriptionStatus.ACTIVE)
            throw ValidationException("Studio ma już aktywną subskrypcję.")

        val trialEndsAt = Instant.now().plus(TRIAL_DURATION_DAYS, ChronoUnit.DAYS)
        entity.subscriptionStatus = SubscriptionStatus.TRIALING
        entity.trialEndsAt = trialEndsAt
        entity.trialUsed = true
        studioRepository.save(entity)

        logger.info("Studio={} started free trial, ends at {}", studioId, trialEndsAt)
        entity.toDomain().toSubscriptionInfo()
    }

    suspend fun createStudioWithTrial(name: String): Studio = withContext(Dispatchers.IO) {
        val trialEndsAt = Instant.now().plus(TRIAL_DURATION_DAYS, ChronoUnit.DAYS)

        val studio = Studio(
            id = StudioId.random(),
            name = name,
            subscriptionStatus = SubscriptionStatus.TRIALING,
            trialEndsAt = trialEndsAt,
            subscriptionEndsAt = null,
            trialUsed = true,
            createdAt = Instant.now(),
            emailAlias = UUID.randomUUID().toString().replace("-", "")
        )

        studioRepository.save(StudioEntity.fromDomain(studio))
        studio
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    suspend fun getStudio(studioId: StudioId): Studio = withContext(Dispatchers.IO) {
        studioRepository.findByStudioId(studioId.value)?.toDomain()
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
    }

    suspend fun validateAccess(studioId: StudioId) = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)
        if (!studio.isAccessible()) {
            throw ForbiddenException("Brak dostępu. Status subskrypcji: ${studio.subscriptionStatus}")
        }
    }

    suspend fun getSubscriptionInfo(studioId: StudioId): SubscriptionInfo = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)
        studio.toSubscriptionInfo()
    }

    // ─── Maintenance ──────────────────────────────────────────────────────────

    @Transactional
    suspend fun expireTrials() = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val expiredStudios = studioRepository.findExpiredTrials(now)

        expiredStudios.forEach { entity ->
            entity.subscriptionStatus = SubscriptionStatus.EXPIRED
            studioRepository.save(entity)
        }

        expiredStudios.size
    }

    @Transactional
    suspend fun expireSubscriptions() = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val expiredStudios = studioRepository.findExpiredSubscriptions(now)

        expiredStudios.forEach { entity ->
            entity.subscriptionStatus = SubscriptionStatus.EXPIRED
            studioRepository.save(entity)
        }

        expiredStudios.size
    }
}

// ─── Supporting types ─────────────────────────────────────────────────────────

data class SubscriptionInfo(
    val status: SubscriptionStatus,
    val daysRemaining: Long?,
    val subscriptionEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val isAccessible: Boolean,
    val trialUsed: Boolean
)

private fun Studio.toSubscriptionInfo() = SubscriptionInfo(
    status = subscriptionStatus,
    daysRemaining = getDaysRemaining(),
    subscriptionEndsAt = subscriptionEndsAt,
    trialEndsAt = trialEndsAt,
    isAccessible = isAccessible(),
    trialUsed = trialUsed
)
