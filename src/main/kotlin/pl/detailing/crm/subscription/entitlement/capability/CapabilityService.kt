package pl.detailing.crm.subscription.entitlement.capability

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.CapabilityLockedException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey

/**
 * The single decision point for "can this studio perform this action?".
 *
 * Every enforcement layer asks this service — and only this service:
 *  - W1: domain services performing side effects (communication gateway,
 *        invoice orchestrator, signature request handler),
 *  - W2: the REST layer via [RequiresCapability] + [CapabilityAuthorizationAspect],
 *  - W3: background dispatchers iterating over studios,
 *  - W4: the frontend, indirectly, via GET /api/v1/me/entitlements which returns
 *        the RESOLVED capability map — the UI never re-evaluates expressions.
 *
 * The evaluation itself is trivial (set containment over the Redis-cached
 * [pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements]), so
 * calling [hasCapability] on hot paths costs a cache hit, not a DB query.
 *
 * IMPORTANT: capability checks are entitlement checks (what the STUDIO bought),
 * fully independent from RBAC (what the USER may do). Studio owners bypass RBAC,
 * but never bypass capabilities.
 */
@Service
class CapabilityService(
    private val entitlementService: EntitlementService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** True when the studio's enabled features satisfy the capability's expression. */
    fun hasCapability(studioId: StudioId, capability: CapabilityKey): Boolean =
        missingFeatures(studioId, capability).isEmpty()

    /** The features the studio lacks for this capability; empty means allowed. */
    fun missingFeatures(studioId: StudioId, capability: CapabilityKey): Set<FeatureKey> {
        val enabled = entitlementService.getEntitlements(studioId).enabledFeatures
        return capability.missingFeaturesFor(enabled)
    }

    /**
     * Fail-closed guard for enforcement points. Throws [CapabilityLockedException]
     * (→ HTTP 402, code MODULE_REQUIRED) carrying the exact missing features and
     * checkout-ready upsell options, so callers never build paywall payloads by hand.
     */
    fun requireCapability(studioId: StudioId, capability: CapabilityKey) {
        val decision = resolveOne(studioId, capability)
        if (!decision.enabled) {
            logger.info(
                "Capability denied: studio={} capability={} missingFeatures={}",
                studioId, capability, decision.missingFeatures
            )
            throw CapabilityLockedException(
                capability = capability,
                missingFeatures = decision.missingFeatures,
                upsell = decision.upsell
            )
        }
    }

    /** Resolves a single capability with upsell metadata for the missing features. */
    fun resolveOne(studioId: StudioId, capability: CapabilityKey): CapabilityDecision {
        val missing = missingFeatures(studioId, capability)
        if (missing.isEmpty()) return CapabilityDecision.allowed(capability)
        return CapabilityDecision(
            capability = capability,
            enabled = false,
            missingFeatures = missing,
            upsell = upsellOptionsFor(missing, entitlementService.getAllAddOns())
        )
    }

    /**
     * Resolves the full capability map for a studio.
     * One entitlement lookup (Redis-cached) + at most one add-on catalog read,
     * fetched lazily only when some capability is disabled and shared by all of them.
     */
    fun resolve(studioId: StudioId): StudioCapabilities {
        val enabled = entitlementService.getEntitlements(studioId).enabledFeatures
        val addOnCatalog by lazy { entitlementService.getAllAddOns() }

        val decisions = CapabilityKey.entries.associateWith { capability ->
            val missing = capability.missingFeaturesFor(enabled)
            if (missing.isEmpty()) {
                CapabilityDecision.allowed(capability)
            } else {
                CapabilityDecision(
                    capability = capability,
                    enabled = false,
                    missingFeatures = missing,
                    upsell = upsellOptionsFor(missing, addOnCatalog)
                )
            }
        }
        return StudioCapabilities(decisions)
    }

    /**
     * Maps missing features to the purchasable add-ons that provide them.
     * Features provided only by a plan upgrade produce no add-on option — the
     * frontend then falls back to the plan-upgrade CTA.
     */
    private fun upsellOptionsFor(
        missing: Set<FeatureKey>,
        addOnCatalog: List<pl.detailing.crm.subscription.entitlement.domain.AddOn>
    ): List<CapabilityUpsellOption> =
        addOnCatalog
            .filter { addOn -> addOn.features.any { it in missing } }
            .map { addOn ->
                CapabilityUpsellOption(
                    addOnKey = addOn.key.name,
                    addOnName = addOn.name,
                    monthlyPriceGrossCents = addOn.monthlyPriceGrossCents,
                    providesFeatures = addOn.features.intersect(missing),
                    isAvailable = addOn.isAvailable
                )
            }
}
