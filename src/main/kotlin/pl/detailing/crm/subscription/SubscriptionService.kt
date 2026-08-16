package pl.detailing.crm.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    private val studioRepository: StudioRepository,
    private val entitlementService: EntitlementService,
    private val studioProvisioningService: StudioProvisioningService,
    private val defaultProtocolTemplateProvisioner: DefaultProtocolTemplateProvisioner
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TRIAL_DURATION_DAYS = 60L
    }

    /**
     * Seed the default vehicle-acceptance protocol template for a freshly created
     * studio. Failure must never break signup — the startup backfill and the
     * delete-time guards will heal the studio later.
     */
    private fun seedDefaultProtocolTemplate(studioId: StudioId) {
        try {
            defaultProtocolTemplateProvisioner.ensureDefaultCheckInTemplate(studioId)
        } catch (e: Exception) {
            logger.error("Failed to seed default protocol template for studio {}: {}", studioId, e.message, e)
        }
    }

    // ─── Studio creation ──────────────────────────────────────────────────────

    /**
     * Creates a studio with no billing plan chosen yet.
     *
     * PROVISIONING INVARIANT: the studio and its `studio_subscription_plans` row
     * (BASIC feature-plan) are created atomically by [StudioProvisioningService].
     * Billing status (NO_PLAN → trial/paid) and the feature-plan row are separate
     * concerns — the row must exist from day one so add-on purchases and
     * entitlement reads never depend on a silent fallback. This closes the root
     * cause of "Studio nie ma aktywnego planu subskrypcji" thrown after a
     * captured payment.
     */
    /**
     * Not `suspend` on purpose: its only caller (SignupHandler) already runs on
     * Dispatchers.IO and needs to invoke this inside a TransactionTemplate, so that the
     * studio and its owner commit together. Both calls below are plain blocking
     * functions, so dropping the extra dispatcher hop changes nothing at runtime.
     */
    fun createStudio(name: String): Studio {
        val studio = studioProvisioningService.provisionStudio(name)
        seedDefaultProtocolTemplate(studio.id)
        return studio
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

        // Legacy studios created before the provisioning invariant may lack the row.
        entitlementService.ensurePlanAssigned(studioId)

        logger.info("Studio={} started free trial, ends at {}", studioId, trialEndsAt)
        entity.toDomain().toSubscriptionInfo()
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
