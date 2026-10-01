package pl.detailing.crm.subscription.entitlement.capability

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.CapabilityLockedException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionInactiveException
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import pl.detailing.crm.subscription.lifecycle.SubscriptionProperties

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
 *
 * Capability = kupiony moduł × aktywna subskrypcja. Wcześniej liczył się tylko kupiony
 * plan: studio po wygaśnięciu nadal „miało" FULL, więc automatyzacje SMS, przypomnienia
 * i kampanie (wszystkie przechodzą przez tę klasę) działały w tle bez opłaty, a ścieżki
 * wyłączone z interceptora HTTP (aplikacja mobilna, tablet) wpuszczały do modułów
 * (audyt, S3, J4). Stan subskrypcji pochodzi z tego samego wpisu w cache'u co plan,
 * a decyzja z [SubscriptionAccessPolicy] — tej samej, której używa interceptor.
 */
@Service
class CapabilityService(
    private val entitlementService: EntitlementService,
    private val accessPolicy: SubscriptionAccessPolicy = SubscriptionAccessPolicy(SubscriptionProperties())
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** True when the subscription is usable and the studio's enabled features satisfy the capability. */
    fun hasCapability(studioId: StudioId, capability: CapabilityKey): Boolean {
        val entitlements = entitlementService.getEntitlements(studioId)
        return subscriptionUsable(entitlements) && capability.missingFeaturesFor(entitlements.enabledFeatures).isEmpty()
    }

    /** The features the studio lacks for this capability (independent of subscription state); empty means bought. */
    fun missingFeatures(studioId: StudioId, capability: CapabilityKey): Set<FeatureKey> {
        val enabled = entitlementService.getEntitlements(studioId).enabledFeatures
        return capability.missingFeaturesFor(enabled)
    }

    /** Czy studio może teraz korzystać z kupionych modułów (trial, opłacony okres, karencja). */
    fun isSubscriptionUsable(studioId: StudioId): Boolean =
        subscriptionUsable(entitlementService.getEntitlements(studioId))

    /**
     * Fail-closed guard for enforcement points.
     *  - nieaktywna subskrypcja → [SubscriptionInactiveException] (→ HTTP 403 SUBSCRIPTION_INACTIVE,
     *    ten sam sygnał co z interceptora),
     *  - brak modułu → [CapabilityLockedException] (→ HTTP 402, code MODULE_REQUIRED) carrying
     *    the exact missing features and checkout-ready upsell options.
     */
    fun requireCapability(studioId: StudioId, capability: CapabilityKey) {
        val decision = resolveOne(studioId, capability)
        if (decision.enabled) return

        if (decision.lockedBy == CapabilityLock.SUBSCRIPTION) {
            logger.info("Capability denied (subscription inactive): studio={} capability={}", studioId, capability)
            throw SubscriptionInactiveException()
        }
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

    /** Resolves a single capability with upsell metadata for the missing features. */
    fun resolveOne(studioId: StudioId, capability: CapabilityKey): CapabilityDecision {
        val entitlements = entitlementService.getEntitlements(studioId)
        if (!subscriptionUsable(entitlements)) return CapabilityDecision.subscriptionInactive(capability)

        val missing = capability.missingFeaturesFor(entitlements.enabledFeatures)
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
        val entitlements = entitlementService.getEntitlements(studioId)
        if (!subscriptionUsable(entitlements)) {
            return StudioCapabilities(CapabilityKey.entries.associateWith { CapabilityDecision.subscriptionInactive(it) })
        }

        val enabled = entitlements.enabledFeatures
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
     * Brak stanu rozliczeniowego zdarza się tylko w obiektach budowanych ręcznie (testy) —
     * wczytanie z bazy zawsze go ustawia, a brak studia daje NO_PLAN, czyli odmowę.
     */
    private fun subscriptionUsable(entitlements: StudioEntitlements): Boolean =
        entitlements.billing?.let { accessPolicy.isAccessible(it) } ?: true

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
