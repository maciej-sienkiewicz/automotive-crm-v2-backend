package pl.detailing.crm.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Subscription lifecycle: studio creation, trial management and status queries.
 * Przejścia wynikające z upływu czasu (trial → EXPIRED, ACTIVE → PAST_DUE → EXPIRED)
 * wykonuje [pl.detailing.crm.subscription.lifecycle.SubscriptionLifecycleJob].
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
    private val defaultProtocolTemplateProvisioner: DefaultProtocolTemplateProvisioner,
    private val defaultMarketingConsentProvisioner: DefaultMarketingConsentProvisioner,
    private val accessPolicy: SubscriptionAccessPolicy
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
            defaultProtocolTemplateProvisioner.ensureDefaultCheckOutTemplate(studioId)
        } catch (e: Exception) {
            logger.error("Failed to seed default protocol template for studio {}: {}", studioId, e.message, e)
        }
    }

    /**
     * Seed the bundled marketing-consent document. Osobny try/catch od protokołu:
     * brak zgody to brak wysyłek marketingowych, ale nie powód, żeby rejestracja
     * albo systemowy protokół przyjęcia miały się nie udać.
     */
    private fun seedDefaultMarketingConsent(studioId: StudioId) {
        try {
            defaultMarketingConsentProvisioner.ensureDefaultMarketingConsent(studioId)
        } catch (e: Exception) {
            logger.error("Failed to seed default marketing consent for studio {}: {}", studioId, e.message, e)
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
        seedDefaultMarketingConsent(studio.id)
        return studio
    }

    /**
     * Starts the free trial for a studio that has never used one.
     *
     * Zwykła funkcja z prawdziwą transakcją i blokadą wiersza studia — wcześniej `suspend`
     * z `withContext(Dispatchers.IO)` pod `@Transactional`, czyli bez transakcji: zapis studia
     * i wiersza planu mogły się rozjechać, a dwa kliknięcia „Rozpocznij trial" ścigały się.
     */
    @Transactional
    fun startTrial(studioId: StudioId): SubscriptionInfo {
        val entity = studioRepository.lockById(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")

        if (entity.trialUsed) throw ValidationException("Okres próbny został już wykorzystany.")
        if (entity.subscriptionStatus == SubscriptionStatus.ACTIVE || entity.subscriptionStatus == SubscriptionStatus.PAST_DUE)
            throw ValidationException("Studio ma już aktywną subskrypcję.")

        val trialEndsAt = accessPolicy.now().plus(TRIAL_DURATION_DAYS, ChronoUnit.DAYS)
        entity.subscriptionStatus = SubscriptionStatus.TRIALING
        entity.trialEndsAt = trialEndsAt
        entity.graceEndsAt = null
        entity.trialUsed = true

        // Legacy studios created before the provisioning invariant may lack the row.
        entitlementService.ensurePlanAssigned(studioId)
        entitlementService.evictEntitlementsCache(studioId)

        logger.info("Studio={} started free trial, ends at {}", studioId, trialEndsAt)
        return entity.toDomain().toSubscriptionInfo(accessPolicy)
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    suspend fun getStudio(studioId: StudioId): Studio = withContext(Dispatchers.IO) {
        studioRepository.findByStudioId(studioId.value)?.toDomain()
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
    }

    /**
     * Bramka dostępu do API. Decyzja z [SubscriptionAccessPolicy] — tej samej, której używa
     * [pl.detailing.crm.subscription.entitlement.capability.CapabilityService] dla zadań w tle
     * i job cyklu życia, więc w tej samej chwili wszystkie trzy mówią to samo.
     */
    suspend fun validateAccess(studioId: StudioId) = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)
        if (!accessPolicy.isAccessible(studio.billing())) {
            throw ForbiddenException("Brak dostępu. Status subskrypcji: ${studio.subscriptionStatus}")
        }
    }

    suspend fun getSubscriptionInfo(studioId: StudioId): SubscriptionInfo = withContext(Dispatchers.IO) {
        getStudio(studioId).toSubscriptionInfo(accessPolicy)
    }
}

// ─── Supporting types ─────────────────────────────────────────────────────────

data class SubscriptionInfo(
    val status: SubscriptionStatus,
    /** Dni do końca dostępu: triala, opłaconego okresu, a w karencji — do końca karencji. */
    val daysRemaining: Long?,
    val subscriptionEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val isAccessible: Boolean,
    val trialUsed: Boolean,
    /** Koniec karencji — okres minął, dostęp trwa do tej chwili, czeka na odnowienie. */
    val graceEndsAt: Instant? = null,
    val inGrace: Boolean = false
)

internal fun Studio.toSubscriptionInfo(policy: SubscriptionAccessPolicy): SubscriptionInfo {
    val billing = billing()
    val now = policy.now()
    val inGrace = policy.isInGrace(billing, now)
    val accessEndsAt = policy.accessEndsAt(billing)
    return SubscriptionInfo(
        status = subscriptionStatus,
        daysRemaining = accessEndsAt?.let {
            if (it.isAfter(now)) java.time.Duration.between(now, it).toDays() else 0L
        },
        subscriptionEndsAt = subscriptionEndsAt,
        trialEndsAt = trialEndsAt,
        isAccessible = policy.isAccessible(billing, now),
        trialUsed = trialUsed,
        graceEndsAt = if (inGrace) accessEndsAt else null,
        inGrace = inGrace
    )
}
