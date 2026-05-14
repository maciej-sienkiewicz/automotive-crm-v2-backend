package pl.detailing.crm.subscription.entitlement

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.subscription.entitlement.domain.AddOn
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.Plan
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.management.AddOnActivationPreview
import pl.detailing.crm.subscription.management.ChangeType
import pl.detailing.crm.subscription.management.PlanChangePreview
import pl.detailing.crm.subscription.management.PlanManagementService
import pl.detailing.crm.subscription.pricing.PricingService
import pl.detailing.crm.subscription.SubscriptionService
import java.time.Instant

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class FeatureStatusDto(
    val enabled: Boolean,
    val source: String?,          // "PLAN" | "ADD_ON" | null
    val upsell: UpsellDto?
)

data class UpsellDto(
    val addOnKey: String?,
    val addOnName: String?,
    val monthlyPriceGrossCents: Long?,
    val isAvailable: Boolean
)

data class EntitlementsResponse(
    val plan: PlanSummaryDto,
    val features: Map<String, FeatureStatusDto>,
    val activeAddOns: List<String>
)

data class PlanSummaryDto(
    val key: String,
    val name: String,
    val monthlyPriceGrossCents: Long
)

data class PlanDto(
    val key: String,
    val name: String,
    val monthlyPriceGrossCents: Long,
    val features: List<String>,
    val displayOrder: Int
)

data class AddOnDto(
    val key: String,
    val name: String,
    val description: String?,
    val monthlyPriceGrossCents: Long?,
    val features: List<String>,
    val isAvailable: Boolean
)

data class CustomPriceRequest(val addOnKeys: List<AddOnKey>)

data class CustomPriceResponse(
    val basePlanKey: String,
    val basePlanName: String,
    val basePlanMonthlyPriceCents: Long,
    val addOns: List<AddOnPriceLineDto>,
    val totalMonthlyPriceCents: Long?,
    val hasUndefinedPrices: Boolean
)

data class AddOnPriceLineDto(
    val key: String,
    val name: String,
    val monthlyPriceGrossCents: Long?
)

data class AssignPlanRequest(val planKey: PlanKey)
data class ActivateAddOnRequest(val addOnKey: AddOnKey)
data class PreviewPlanChangeRequest(val newPlanKey: PlanKey)
data class PreviewAddOnRequest(val addOnKey: AddOnKey)

/**
 * Unified "my subscription" view — combines billing status with plan/add-on details.
 *
 * [billingStatus]    — TRIALING | ACTIVE | PAST_DUE | EXPIRED
 * [plan]             — currently assigned feature plan
 * [activeAddOns]     — add-ons currently active with their pricing
 * [pendingDowngrade] — scheduled plan change (if any); null when none is pending
 * [periodEndsAt]     — when the current billing period ends (null during trial)
 * [daysRemaining]    — days left in the current billing period
 * [monthlyCostCents] — total monthly cost (plan + add-ons) at the current rates
 */
data class MyPlanResponse(
    val billingStatus: String,
    val plan: PlanSummaryDto,
    val activeAddOns: List<ActiveAddOnDto>,
    val pendingDowngrade: PendingDowngradeDto?,
    val periodEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val daysRemaining: Long?,
    val monthlyCostCents: Long,
    val nextRenewalCostCents: Long?
)

/** Describes a scheduled plan downgrade that has not yet been applied. */
data class PendingDowngradeDto(
    val toPlanKey: String,
    val toPlanName: String,
    val effectiveAt: Instant
)

data class ActiveAddOnDto(
    val key: String,
    val name: String,
    val monthlyPriceGrossCents: Long?
)

data class PlanChangePreviewDto(
    val changeType: String,
    val newPlanKey: String,
    val newPlanName: String,
    val effectiveAt: Instant,
    val proratedAmountCents: Long?,
    val proratedAmountFormatted: String?,
    val daysRemaining: Long?,
    val periodEndsAt: Instant?,
    val explanation: String
)

data class AddOnActivationPreviewDto(
    val addOnKey: String,
    val addOnName: String,
    val proratedAmountCents: Long?,
    val proratedAmountFormatted: String?,
    val daysRemaining: Long?,
    val periodEndsAt: Instant?,
    val explanation: String
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * REST surface for feature entitlements and plan management.
 *
 * Read (no auth/subscription gating on catalog + entitlements):
 *   GET  /api/v1/me/entitlements                  → feature map for frontend rendering
 *   GET  /api/v1/subscription/my-plan             → unified billing + plan view (OWNER)
 *   GET  /api/v1/subscription/feature-plans       → full plan catalog
 *   GET  /api/v1/subscription/add-ons             → full add-on catalog
 *   POST /api/v1/subscription/calculate-price     → dynamic custom plan pricing
 *
 * Preview (before committing changes):
 *   POST /api/v1/subscription/preview-plan-change → shows proration/timing before change
 *   POST /api/v1/subscription/preview-add-on      → shows prorated cost before activating
 *
 * Mutation (OWNER only, with billing):
 *   POST   /api/v1/subscription/change-plan       → upgrade (immediate) or downgrade (deferred)
 *   POST   /api/v1/subscription/activate-add-on   → immediate + prorated billing
 *   DELETE /api/v1/subscription/add-ons/{key}     → deactivate add-on (end of period)
 */
@RestController
class EntitlementsController(
    private val entitlementService: EntitlementService,
    private val pricingService: PricingService,
    private val planManagementService: PlanManagementService,
    private val subscriptionService: SubscriptionService
) {

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * The primary endpoint for frontend feature-gating.
     * Returns enabled/disabled status for every feature with upsell metadata for locked ones.
     * Never throws 403 — locked features appear as {enabled: false, upsell: {...}}.
     */
    @GetMapping("/api/v1/me/entitlements")
    fun getMyEntitlements(): ResponseEntity<EntitlementsResponse> {
        val studioId = SecurityContextHelper.getCurrentStudioId()
        val entitlements = entitlementService.getEntitlements(studioId)
        val allAddOns = entitlementService.getAllAddOns()
        val plans = entitlementService.getAllPlans()

        val planSummary = plans.firstOrNull { it.key == entitlements.planKey } ?: plans.first()
        val featureMap = buildFeatureMap(entitlements, allAddOns)

        return ResponseEntity.ok(
            EntitlementsResponse(
                plan = PlanSummaryDto(
                    key = planSummary.key.name,
                    name = planSummary.name,
                    monthlyPriceGrossCents = planSummary.monthlyPriceGrossCents
                ),
                features = featureMap,
                activeAddOns = entitlements.activeAddOnKeys.map { it.name }
            )
        )
    }

    /**
     * Unified "my subscription" view for the account/billing settings screen.
     * Combines billing lifecycle data with the current feature plan, active add-ons,
     * and any scheduled plan downgrade waiting to be applied at period end.
     */
    @GetMapping("/api/v1/subscription/my-plan")
    fun getMyPlan(): ResponseEntity<MyPlanResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentUser().studioId

        val billingInfo = subscriptionService.getSubscriptionInfo(studioId)
        val entitlements = entitlementService.getEntitlements(studioId)
        val allAddOns = entitlementService.getAllAddOns()
        val plans = entitlementService.getAllPlans()

        val planSummary = plans.firstOrNull { it.key == entitlements.planKey } ?: plans.first()

        val activeAddOnDtos = entitlements.activeAddOnKeys.mapNotNull { addOnKey ->
            allAddOns.find { it.key == addOnKey }?.let { addOn ->
                ActiveAddOnDto(key = addOn.key.name, name = addOn.name, monthlyPriceGrossCents = addOn.monthlyPriceGrossCents)
            }
        }

        val pendingDowngrade = planManagementService.getPendingDowngrade(studioId)?.let { pending ->
            val targetPlan = plans.firstOrNull { it.key == pending.toPlanKey }
            PendingDowngradeDto(
                toPlanKey = pending.toPlanKey.name,
                toPlanName = targetPlan?.name ?: pending.toPlanKey.displayName,
                effectiveAt = pending.effectiveAt
            )
        }

        val monthlyCostCents = planSummary.monthlyPriceGrossCents + activeAddOnDtos.sumOf { it.monthlyPriceGrossCents ?: 0L }

        return ResponseEntity.ok(
            MyPlanResponse(
                billingStatus = billingInfo.status.name,
                plan = PlanSummaryDto(key = planSummary.key.name, name = planSummary.name, monthlyPriceGrossCents = planSummary.monthlyPriceGrossCents),
                activeAddOns = activeAddOnDtos,
                pendingDowngrade = pendingDowngrade,
                periodEndsAt = billingInfo.subscriptionEndsAt,
                trialEndsAt = billingInfo.trialEndsAt,
                daysRemaining = billingInfo.daysRemaining,
                monthlyCostCents = monthlyCostCents,
                nextRenewalCostCents = if (billingInfo.isAccessible) monthlyCostCents else null
            )
        )
    }

    /**
     * Cancels a scheduled plan downgrade.
     * The studio keeps its current plan for the full billing period.
     * Returns 204 if cancelled, 404 if there was no pending downgrade.
     */
    @DeleteMapping("/api/v1/subscription/pending-plan-change")
    fun cancelPendingDowngrade(): ResponseEntity<Void> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        val cancelled = planManagementService.cancelPendingDowngrade(studioId)
        return if (cancelled) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
    }

    @GetMapping("/api/v1/subscription/feature-plans")
    fun getPlans(): ResponseEntity<List<PlanDto>> =
        ResponseEntity.ok(entitlementService.getAllPlans().map { it.toDto() })

    @GetMapping("/api/v1/subscription/add-ons")
    fun getAddOns(): ResponseEntity<List<AddOnDto>> =
        ResponseEntity.ok(entitlementService.getAllAddOns().map { it.toDto() })

    @PostMapping("/api/v1/subscription/calculate-price")
    fun calculateCustomPrice(@RequestBody request: CustomPriceRequest): ResponseEntity<CustomPriceResponse> =
        ResponseEntity.ok(pricingService.calculateCustomPrice(request.addOnKeys))

    // ── Preview ───────────────────────────────────────────────────────────────

    /**
     * Returns a preview of what a plan change would cost and when it would take effect.
     * Call this before showing the confirmation dialog — no side effects.
     */
    @PostMapping("/api/v1/subscription/preview-plan-change")
    fun previewPlanChange(@RequestBody request: PreviewPlanChangeRequest): ResponseEntity<PlanChangePreviewDto> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        val preview = planManagementService.previewPlanChange(studioId, request.newPlanKey)
        return ResponseEntity.ok(preview.toDto())
    }

    /**
     * Returns a preview of the prorated cost for activating an add-on today.
     * Call this before showing the purchase confirmation — no side effects.
     */
    @PostMapping("/api/v1/subscription/preview-add-on")
    fun previewAddOnActivation(@RequestBody request: PreviewAddOnRequest): ResponseEntity<AddOnActivationPreviewDto> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        val preview = planManagementService.previewAddOnActivation(studioId, request.addOnKey)
        return ResponseEntity.ok(preview.toDto())
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Changes the studio's plan with appropriate billing:
     * - Upgrade → immediate, prorated charge for the price difference
     * - Downgrade → deferred to end of billing period, no charge
     *
     * Always preview first via POST /preview-plan-change.
     */
    @PostMapping("/api/v1/subscription/change-plan")
    fun changePlan(@RequestBody request: AssignPlanRequest): ResponseEntity<EntitlementsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        planManagementService.changePlan(studioId, request.planKey)
        return getMyEntitlements()
    }

    /**
     * Activates an add-on immediately with prorated billing for remaining days.
     * Preview first via POST /preview-add-on.
     */
    @PostMapping("/api/v1/subscription/activate-add-on")
    fun activateAddOn(@RequestBody request: ActivateAddOnRequest): ResponseEntity<EntitlementsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        planManagementService.activateAddOnWithBilling(studioId, request.addOnKey)
        return getMyEntitlements()
    }

    /**
     * Deactivates an add-on. Features are removed immediately from the entitlement cache.
     * No refund is issued for the remaining period.
     */
    @DeleteMapping("/api/v1/subscription/add-ons/{key}")
    fun deactivateAddOn(@PathVariable key: AddOnKey): ResponseEntity<EntitlementsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        planManagementService.deactivateAddOnWithLog(studioId, key)
        return getMyEntitlements()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requireOwner() {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel studia może zarządzać subskrypcją")
        }
    }

    private fun buildFeatureMap(
        entitlements: pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements,
        allAddOns: List<AddOn>
    ): Map<String, FeatureStatusDto> {
        val addOnByFeature: Map<FeatureKey, AddOn> = allAddOns
            .flatMap { addOn -> addOn.features.map { feature -> feature to addOn } }
            .toMap()

        val addOnFeatureKeys: Set<FeatureKey> = entitlements.activeAddOnKeys
            .flatMap { key -> allAddOns.find { it.key == key }?.features ?: emptySet() }
            .toSet()

        return FeatureKey.entries.associate { featureKey ->
            val enabled = entitlements.hasFeature(featureKey)
            val source = when {
                !enabled -> null
                featureKey in addOnFeatureKeys -> "ADD_ON"
                else -> "PLAN"
            }
            val upsell = if (!enabled) {
                val relevantAddOn = addOnByFeature[featureKey]
                UpsellDto(
                    addOnKey = relevantAddOn?.key?.name,
                    addOnName = relevantAddOn?.name,
                    monthlyPriceGrossCents = relevantAddOn?.monthlyPriceGrossCents,
                    isAvailable = relevantAddOn?.isAvailable ?: false
                )
            } else null

            featureKey.name to FeatureStatusDto(enabled = enabled, source = source, upsell = upsell)
        }
    }
}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun Plan.toDto() = PlanDto(
    key = key.name,
    name = name,
    monthlyPriceGrossCents = monthlyPriceGrossCents,
    features = features.map { it.name },
    displayOrder = displayOrder
)

private fun AddOn.toDto() = AddOnDto(
    key = key.name,
    name = name,
    description = description,
    monthlyPriceGrossCents = monthlyPriceGrossCents,
    features = features.map { it.name },
    isAvailable = isAvailable
)

private fun PlanChangePreview.toDto() = PlanChangePreviewDto(
    changeType = changeType.name,
    newPlanKey = newPlanKey.name,
    newPlanName = newPlanName,
    effectiveAt = effectiveAt,
    proratedAmountCents = proratedAmountCents,
    proratedAmountFormatted = proratedAmountCents?.let {
        java.math.BigDecimal(it).movePointLeft(2).toPlainString() + " PLN"
    },
    daysRemaining = daysRemaining,
    periodEndsAt = periodEndsAt,
    explanation = explanation
)

private fun AddOnActivationPreview.toDto() = AddOnActivationPreviewDto(
    addOnKey = addOnKey.name,
    addOnName = addOnName,
    proratedAmountCents = proratedAmountCents,
    proratedAmountFormatted = proratedAmountCents?.let {
        java.math.BigDecimal(it).movePointLeft(2).toPlainString() + " PLN"
    },
    daysRemaining = daysRemaining,
    periodEndsAt = periodEndsAt,
    explanation = explanation
)
