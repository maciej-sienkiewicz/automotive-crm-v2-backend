package pl.detailing.crm.subscription

import kotlinx.coroutines.runBlocking
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
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

/**
 * Single entry in the payment/event history.
 *
 * [eventType] — one of: SUBSCRIPTION_PURCHASE, PLAN_UPGRADE, PLAN_DOWNGRADE,
 *                        ADD_ON_ACTIVATION, ADD_ON_DEACTIVATION
 * [amountCents] — 0 for events with no charge (downgrades, deactivations).
 * [plan]        — the plan that was active at the time of the event, or null if unknown
 *                 (legacy entries created before feature-plan tracking was introduced).
 * [addOn]       — populated only for add-on events.
 */
data class PaymentHistoryEntryDto(
    val id: String,
    val date: Instant,
    val eventType: String,
    val eventTypeDisplayName: String,
    val description: String,
    val amountCents: Long,
    val amountFormatted: String,
    val currency: String,
    val transactionId: String?,
    val plan: PaymentPlanSnapshotDto?,
    val addOn: PaymentAddOnSnapshotDto?
)

data class PaymentPlanSnapshotDto(
    val key: String,
    val name: String
)

data class PaymentAddOnSnapshotDto(
    val key: String,
    val name: String
)

data class PaymentHistoryResponse(
    val entries: List<PaymentHistoryEntryDto>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * REST surface for subscription billing management.
 *
 * All endpoints are exempt from SubscriptionInterceptor (see WebMvcConfig),
 * so expired studios can still reach them to renew their subscription.
 *
 * GET  /api/v1/subscription/status          → current subscription state
 * POST /api/v1/subscription/start-trial     → start the free trial (OWNER only)
 * GET  /api/v1/subscription/payment-history → full billing event history (OWNER only)
 *
 * Purchases and renewals are handled by the payments module
 * (POST /api/v1/subscription/checkout → Przelewy24).
 */
@RestController
@RequestMapping("/api/v1/subscription")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository
) {

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<SubscriptionStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val info = subscriptionService.getSubscriptionInfo(principal.studioId)
        ResponseEntity.ok(info.toResponse())
    }

    @PostMapping("/start-trial")
    fun startTrial(): ResponseEntity<SubscriptionStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Uruchomienie trialu jest dostępne wyłącznie dla właściciela studia")
        }
        val info = subscriptionService.startTrial(principal.studioId)
        ResponseEntity.ok(info.toResponse())
    }

    /**
     * Returns a paginated history of all subscription billing events for the studio:
     * initial purchases, plan upgrades/downgrades, add-on activations/deactivations.
     *
     * Each entry includes a snapshot of:
     *   - [PaymentPlanSnapshotDto] — the plan active at the time of the event
     *   - [PaymentAddOnSnapshotDto] — the add-on involved (for add-on events only)
     *
     * Entries are sorted newest-first.
     *
     * Query params:
     *   page     (default 0)  — zero-based page index
     *   pageSize (default 20, max 100)
     */
    @GetMapping("/payment-history")
    fun getPaymentHistory(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") pageSize: Int
    ): ResponseEntity<PaymentHistoryResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Historia płatności dostępna wyłącznie dla właściciela studia")
        }

        val studioId = principal.studioId
        val clampedPageSize = pageSize.coerceIn(1, 100)

        val planCache = planRepository.findAll().associateBy { it.key }
        val addOnCache = addOnRepository.findAll().associateBy { it.key }

        val logEntries = paymentLogRepository.findAllByStudioIdOrderByCreatedAtDesc(
            studioId.value,
            PageRequest.of(page, clampedPageSize)
        )
        val total = paymentLogRepository.countByStudioId(studioId.value)

        val entries = logEntries.map { entry ->
            val planSnapshot = entry.planKey?.let { key ->
                planCache[key]?.let { PaymentPlanSnapshotDto(key = key.name, name = it.name) }
                    ?: PaymentPlanSnapshotDto(key = key.name, name = key.displayName)
            }
            val addOnSnapshot = entry.addOnKey?.let { key ->
                addOnCache[key]?.let { PaymentAddOnSnapshotDto(key = key.name, name = it.name) }
                    ?: PaymentAddOnSnapshotDto(key = key.name, name = key.displayName)
            }

            PaymentHistoryEntryDto(
                id = entry.id.toString(),
                date = entry.createdAt,
                eventType = entry.eventType.name,
                eventTypeDisplayName = entry.eventType.displayName,
                description = entry.description,
                amountCents = entry.amountInCents,
                amountFormatted = BigDecimal(entry.amountInCents).movePointLeft(2).toPlainString() + " ${entry.currency}",
                currency = entry.currency,
                transactionId = entry.transactionId,
                plan = planSnapshot,
                addOn = addOnSnapshot
            )
        }

        return ResponseEntity.ok(
            PaymentHistoryResponse(
                entries = entries,
                total = total,
                page = page,
                pageSize = clampedPageSize
            )
        )
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
