package pl.detailing.crm.subscription.entitlement

import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.domain.*
import pl.detailing.crm.subscription.entitlement.infrastructure.*

/**
 * Core entitlement service. Single source of truth for "what can a studio do?".
 *
 * Caching strategy:
 * - Resolved entitlements are cached in Redis under key "studio-entitlements::{studioId}"
 * - TTL is configured in CacheConfig (default 5 minutes)
 * - Cache is evicted immediately on plan change or add-on purchase/cancellation
 *   via [evictEntitlementsCache] — called by [SubscriptionService] after every mutation
 */
@Service
class EntitlementService(
    private val studioSubscriptionPlanRepository: StudioSubscriptionPlanRepository,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached entitlements for the given studio.
     * Falls back to BASIC plan entitlements if no explicit plan has been assigned yet
     * (e.g., during trial period before first purchase).
     */
    @Cacheable(value = ["studio-entitlements"], key = "#studioId.toString()")
    @Transactional(readOnly = true)
    fun getEntitlements(studioId: StudioId): StudioEntitlements {
        logger.debug("Loading entitlements from DB for studio={}", studioId)

        val subscription = studioSubscriptionPlanRepository.findByStudioIdWithAddOns(studioId.value)
            ?: return defaultTrialEntitlements()

        val planFeatures = subscription.plan.features.map { it.key }.toSet()
        val addOnFeatures = subscription.activeAddOns.flatMap { it.addOn.features.map { f -> f.key } }.toSet()
        val activeAddOnKeys = subscription.activeAddOns.map { it.addOn.key }.toSet()

        return StudioEntitlements(
            planKey = subscription.plan.key,
            planName = subscription.plan.name,
            enabledFeatures = planFeatures + addOnFeatures,
            activeAddOnKeys = activeAddOnKeys
        )
    }

    fun hasFeature(studioId: StudioId, featureKey: FeatureKey): Boolean =
        getEntitlements(studioId).hasFeature(featureKey)

    @Transactional(readOnly = true)
    fun getAllPlans(): List<Plan> = planRepository.findAllByIsActiveTrue()
        .sortedBy { it.displayOrder }
        .map { it.toDomain() }

    @Transactional(readOnly = true)
    fun getAllAddOns(): List<AddOn> = addOnRepository.findAllByIsActiveTrue()
        .map { it.toDomain() }

    // ── Plan / Add-on management ──────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = ["studio-entitlements"], key = "#studioId.toString()")
    fun assignPlan(studioId: StudioId, planKey: PlanKey): StudioEntitlements {
        val plan = planRepository.findByKey(planKey)
            ?: throw EntityNotFoundException("Plan not found: $planKey")

        val existing = studioSubscriptionPlanRepository.findByStudioId(studioId.value)

        val entity = if (existing != null) {
            existing.activeAddOns.clear()
            StudioSubscriptionPlanEntity(
                id = existing.id,
                studioId = studioId.value,
                plan = plan,
                activatedAt = java.time.Instant.now(),
                createdAt = existing.createdAt
            )
        } else {
            StudioSubscriptionPlanEntity(studioId = studioId.value, plan = plan)
        }

        studioSubscriptionPlanRepository.save(entity)
        logger.info("Studio={} assigned to plan={}", studioId, planKey)

        return getEntitlements(studioId)
    }

    @Transactional
    @CacheEvict(value = ["studio-entitlements"], key = "#studioId.toString()")
    fun activateAddOn(studioId: StudioId, addOnKey: AddOnKey): StudioEntitlements {
        val subscription = studioSubscriptionPlanRepository.findByStudioIdWithAddOns(studioId.value)
            ?: throw EntityNotFoundException("Studio has no active subscription plan: $studioId")

        val alreadyActive = subscription.activeAddOns.any { it.addOn.key == addOnKey }
        if (alreadyActive) return getEntitlements(studioId)

        val addOn = addOnRepository.findByKey(addOnKey)
            ?: throw EntityNotFoundException("Add-on not found: $addOnKey")

        subscription.activeAddOns.add(StudioAddOnEntity(studioSubscriptionPlan = subscription, addOn = addOn))
        studioSubscriptionPlanRepository.save(subscription)

        logger.info("Studio={} activated add-on={}", studioId, addOnKey)
        return getEntitlements(studioId)
    }

    @Transactional
    @CacheEvict(value = ["studio-entitlements"], key = "#studioId.toString()")
    fun deactivateAddOn(studioId: StudioId, addOnKey: AddOnKey) {
        val subscription = studioSubscriptionPlanRepository.findByStudioIdWithAddOns(studioId.value) ?: return
        subscription.activeAddOns.removeIf { it.addOn.key == addOnKey }
        studioSubscriptionPlanRepository.save(subscription)
        logger.info("Studio={} deactivated add-on={}", studioId, addOnKey)
    }

    /**
     * Explicit cache eviction — call this after any billing/subscription mutation
     * to ensure stale entitlements are never served.
     */
    @CacheEvict(value = ["studio-entitlements"], key = "#studioId.toString()")
    fun evictEntitlementsCache(studioId: StudioId) {
        logger.debug("Evicted entitlements cache for studio={}", studioId)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Studios on a free trial get access to the full BASIC plan features
     * without requiring a [StudioSubscriptionPlanEntity] row.
     */
    private fun defaultTrialEntitlements() = StudioEntitlements(
        planKey = PlanKey.BASIC,
        planName = PlanKey.BASIC.displayName,
        enabledFeatures = setOf(
            FeatureKey.CALENDAR,
            FeatureKey.VISITS,
            FeatureKey.CUSTOMERS,
            FeatureKey.VEHICLES,
            FeatureKey.DOCUMENTS,
            FeatureKey.GALLERY
        ),
        activeAddOnKeys = emptySet()
    )
}
