package pl.detailing.crm.subscription

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.subscription.domain.SubscriptionPlan
import pl.detailing.crm.subscription.domain.SubscriptionPlanType
import java.math.BigDecimal
import java.time.Instant

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class SubscriptionStatusResponse(
    val status: SubscriptionStatus,
    val isAccessible: Boolean,
    val daysRemaining: Long?,
    val subscriptionEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val trialUsed: Boolean
)

data class SubscriptionPlanDto(
    val type: SubscriptionPlanType,
    val name: String,
    val durationDays: Int,
    val priceGross: BigDecimal,
    val currency: String,
    val pricePerMonth: BigDecimal
)

data class PurchaseSubscriptionRequest(
    val planType: SubscriptionPlanType
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * REST surface for subscription management.
 *
 * All endpoints are exempt from SubscriptionInterceptor (see WebMvcConfig),
 * so expired studios can still reach them to renew their subscription.
 *
 * GET  /api/v1/subscription/status   → current subscription state (any authenticated user)
 * GET  /api/v1/subscription/plans    → available plans with pricing (any authenticated user)
 * POST /api/v1/subscription/purchase → buy a plan (OWNER only)
 */
@RestController
@RequestMapping("/api/v1/subscription")
class SubscriptionController(
    private val subscriptionService: SubscriptionService
) {

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<SubscriptionStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val info = subscriptionService.getSubscriptionInfo(principal.studioId)
        ResponseEntity.ok(info.toResponse())
    }

    @GetMapping("/plans")
    fun getPlans(): ResponseEntity<List<SubscriptionPlanDto>> {
        val plans = SubscriptionPlan.ALL.map { it.toDto() }
        return ResponseEntity.ok(plans)
    }

    @PostMapping("/purchase")
    fun purchase(
        @RequestBody request: PurchaseSubscriptionRequest
    ): ResponseEntity<SubscriptionStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Zakup subskrypcji jest dostępny wyłącznie dla właściciela studia")
        }

        val info = subscriptionService.purchaseSubscription(principal.studioId, request.planType)
        ResponseEntity.ok(info.toResponse())
    }
}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun SubscriptionInfo.toResponse() = SubscriptionStatusResponse(
    status = status,
    isAccessible = isAccessible,
    daysRemaining = daysRemaining,
    subscriptionEndsAt = subscriptionEndsAt,
    trialEndsAt = trialEndsAt,
    trialUsed = trialUsed
)

private fun SubscriptionPlan.toDto() = SubscriptionPlanDto(
    type = type,
    name = name,
    durationDays = durationDays,
    priceGross = priceGross,
    currency = currency,
    pricePerMonth = when (type) {
        SubscriptionPlanType.MONTHLY -> priceGross
        SubscriptionPlanType.YEARLY  -> priceGross.divide(BigDecimal(12), 2, java.math.RoundingMode.HALF_UP)
    }
)
