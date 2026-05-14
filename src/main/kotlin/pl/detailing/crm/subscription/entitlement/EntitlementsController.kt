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
import pl.detailing.crm.subscription.pricing.PricingService

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

data class CustomPriceRequest(
    val addOnKeys: List<AddOnKey>
)

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

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * REST surface for feature entitlements.
 *
 * GET  /api/v1/me/entitlements          → studio's current feature map (safe for frontend feature-gating)
 * GET  /api/v1/subscription/feature-plans    → full plan catalog
 * GET  /api/v1/subscription/add-ons          → full add-on catalog
 * POST /api/v1/subscription/calculate-price  → dynamic price for a custom plan
 * POST /api/v1/subscription/assign-plan      → assign a plan to the studio (OWNER only)
 * POST /api/v1/subscription/activate-add-on  → activate an add-on (OWNER only)
 * DELETE /api/v1/subscription/add-ons/{key}  → deactivate an add-on (OWNER only)
 */
@RestController
class EntitlementsController(
    private val entitlementService: EntitlementService,
    private val pricingService: PricingService
) {

    /**
     * Returns the complete feature map for the authenticated studio.
     * This is the single endpoint the frontend reads to decide what to render.
     * Never returns 403 — missing features appear as {enabled: false, upsell: {...}}.
     */
    @GetMapping("/api/v1/me/entitlements")
    fun getMyEntitlements(): ResponseEntity<EntitlementsResponse> {
        val studioId = SecurityContextHelper.getCurrentStudioId()
        val entitlements = entitlementService.getEntitlements(studioId)
        val allAddOns = entitlementService.getAllAddOns()
        val plans = entitlementService.getAllPlans()

        val planSummary = plans.firstOrNull { it.key == entitlements.planKey }
            ?: plans.first()

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

    @GetMapping("/api/v1/subscription/feature-plans")
    fun getPlans(): ResponseEntity<List<PlanDto>> {
        val plans = entitlementService.getAllPlans()
        return ResponseEntity.ok(plans.map { it.toDto() })
    }

    @GetMapping("/api/v1/subscription/add-ons")
    fun getAddOns(): ResponseEntity<List<AddOnDto>> {
        val addOns = entitlementService.getAllAddOns()
        return ResponseEntity.ok(addOns.map { it.toDto() })
    }

    @PostMapping("/api/v1/subscription/calculate-price")
    fun calculateCustomPrice(
        @RequestBody request: CustomPriceRequest
    ): ResponseEntity<CustomPriceResponse> {
        val result = pricingService.calculateCustomPrice(request.addOnKeys)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/api/v1/subscription/assign-plan")
    fun assignPlan(@RequestBody request: AssignPlanRequest): ResponseEntity<EntitlementsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        entitlementService.assignPlan(studioId, request.planKey)
        return getMyEntitlements()
    }

    @PostMapping("/api/v1/subscription/activate-add-on")
    fun activateAddOn(@RequestBody request: ActivateAddOnRequest): ResponseEntity<EntitlementsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        entitlementService.activateAddOn(studioId, request.addOnKey)
        return getMyEntitlements()
    }

    @DeleteMapping("/api/v1/subscription/add-ons/{key}")
    fun deactivateAddOn(@PathVariable key: AddOnKey): ResponseEntity<EntitlementsResponse> {
        requireOwner()
        val studioId = SecurityContextHelper.getCurrentStudioId()
        entitlementService.deactivateAddOn(studioId, key)
        return getMyEntitlements()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requireOwner() {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Tylko właściciel studia może zarządzać subskrypcją")
        }
    }

    /**
     * Builds the [FeatureKey] → [FeatureStatusDto] map that tells the frontend
     * exactly which features are available and how to upsell the missing ones.
     */
    private fun buildFeatureMap(
        entitlements: pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements,
        allAddOns: List<AddOn>
    ): Map<String, FeatureStatusDto> {
        val addOnByFeature: Map<FeatureKey, AddOn> = allAddOns
            .flatMap { addOn -> addOn.features.map { feature -> feature to addOn } }
            .toMap()

        return FeatureKey.entries.associate { featureKey ->
            val enabled = entitlements.hasFeature(featureKey)
            val source = when {
                !enabled -> null
                featureKey in entitlements.activeAddOnKeys.flatMap { key ->
                    allAddOns.find { it.key == key }?.features ?: emptySet()
                } -> "ADD_ON"
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
