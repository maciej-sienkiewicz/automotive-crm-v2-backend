package pl.detailing.crm.subscription.management

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.pricing.ProrationService
import java.math.BigDecimal
import java.time.Instant

/**
 * Plan-change previews and the non-payment mutations of a subscription.
 *
 * Money never changes hands here. Paid operations (initial purchase, renewal,
 * upgrade, module purchase) are created as payment orders by
 * [pl.detailing.crm.payments.checkout.CheckoutService] and applied by
 * [pl.detailing.crm.payments.checkout.OrderFulfillmentService] once Przelewy24
 * confirms the transaction. This service handles what's free:
 *
 *   DOWNGRADE  → persisted in [PendingPlanChangeEntity], applied by
 *                [PlanDowngradeScheduler] at period end
 *   CANCEL     → withdraws a pending downgrade
 *   DEACTIVATE → removes an add-on (no refund, features drop immediately)
 */
@Service
class PlanManagementService(
    private val entitlementService: EntitlementService,
    private val prorationService: ProrationService,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ── Previews ──────────────────────────────────────────────────────────────

    /**
     * Previews the cost and timing of switching to [newPlanKey].
     * Does NOT make any changes — safe to call from a confirmation dialog.
     * For UPGRADE the frontend should follow up with POST /checkout (PLAN_UPGRADE).
     */
    fun previewPlanChange(studioId: StudioId, newPlanKey: PlanKey): PlanChangePreview {
        val current = entitlementService.getEntitlements(studioId)
        val currentPlan = planRepository.findByKey(current.planKey)
            ?: throw EntityNotFoundException("Bieżący plan nie został znaleziony: ${current.planKey}")
        val newPlan = planRepository.findByKey(newPlanKey)
            ?: throw EntityNotFoundException("Plan nie został znaleziony: $newPlanKey")

        return when {
            newPlan.monthlyPriceGrossCents > currentPlan.monthlyPriceGrossCents -> {
                val proration = prorationService.calculatePlanUpgrade(
                    studioId,
                    currentPlan.monthlyPriceGrossCents,
                    newPlan.monthlyPriceGrossCents
                )
                PlanChangePreview(
                    changeType = ChangeType.UPGRADE,
                    newPlanKey = newPlanKey,
                    newPlanName = newPlan.name,
                    effectiveAt = Instant.now(),
                    proratedAmountCents = proration?.proratedAmountCents,
                    daysRemaining = proration?.daysRemaining,
                    periodEndsAt = proration?.periodEndsAt,
                    explanation = if (proration != null)
                        "Zostaniesz obciążony kwotą proporcjonalną do pozostałych ${proration.daysRemaining} dni okresu rozliczeniowego."
                    else
                        "Plan zostanie aktywowany natychmiast (jesteś na trialu — pełna cena od następnego odnowienia)."
                )
            }

            newPlan.monthlyPriceGrossCents < currentPlan.monthlyPriceGrossCents -> {
                val periodEndsAt = prorationService.periodEndsAt(studioId)
                PlanChangePreview(
                    changeType = ChangeType.DOWNGRADE,
                    newPlanKey = newPlanKey,
                    newPlanName = newPlan.name,
                    effectiveAt = periodEndsAt ?: Instant.now(),
                    proratedAmountCents = null,
                    daysRemaining = prorationService.daysRemainingInPeriod(studioId),
                    periodEndsAt = periodEndsAt,
                    explanation = if (periodEndsAt != null)
                        "Downgrade wejdzie w życie po zakończeniu bieżącego okresu rozliczeniowego ($periodEndsAt). Do tego czasu zachowujesz dostęp do obecnego planu."
                    else
                        "Plan zostanie zmieniony natychmiast."
                )
            }

            else -> PlanChangePreview(
                changeType = ChangeType.NO_CHANGE,
                newPlanKey = newPlanKey,
                newPlanName = newPlan.name,
                effectiveAt = Instant.now(),
                proratedAmountCents = null,
                daysRemaining = null,
                periodEndsAt = null,
                explanation = "Wybrany plan jest taki sam jak obecny."
            )
        }
    }

    /**
     * Previews the prorated cost of activating an add-on.
     * Does NOT make any changes — the purchase itself goes through checkout.
     */
    fun previewAddOnActivation(studioId: StudioId, addOnKey: AddOnKey): AddOnActivationPreview {
        val addOn = addOnRepository.findByKey(addOnKey)
            ?: throw EntityNotFoundException("Moduł nie został znaleziony: $addOnKey")

        if (!addOn.isAvailable || addOn.monthlyPriceGrossCents == null) {
            return AddOnActivationPreview(
                addOnKey = addOnKey,
                addOnName = addOn.name,
                proratedAmountCents = null,
                daysRemaining = null,
                periodEndsAt = null,
                explanation = "Ten moduł jest jeszcze w przygotowaniu i nie można go aktywować."
            )
        }

        val proration = prorationService.calculateAddOnActivation(studioId, addOn.monthlyPriceGrossCents!!)

        return AddOnActivationPreview(
            addOnKey = addOnKey,
            addOnName = addOn.name,
            proratedAmountCents = proration?.proratedAmountCents,
            daysRemaining = proration?.daysRemaining,
            periodEndsAt = proration?.periodEndsAt,
            explanation = if (proration != null)
                "Zostaniesz obciążony kwotą proporcjonalną za ${proration.daysRemaining} dni pozostałych w bieżącym okresie rozliczeniowym. Od następnego odnowienia pełna cena ${formatCents(addOn.monthlyPriceGrossCents!!)} PLN/mies."
            else
                "Aktywacja bezpłatna w ramach okresu próbnego. Pełna cena ${formatCents(addOn.monthlyPriceGrossCents!!)} PLN/mies. zostanie naliczona od pierwszego odnowienia."
        )
    }

    /** Returns the PENDING downgrade for the studio, if any. */
    fun getPendingDowngrade(studioId: StudioId): PendingPlanChangeEntity? =
        pendingPlanChangeRepository.findByStudioIdAndStatus(studioId.value, PendingPlanChangeStatus.PENDING)

    // ── Free mutations ────────────────────────────────────────────────────────

    /**
     * Schedules a downgrade to a cheaper plan at the end of the billing period.
     * Upgrades are rejected here — they carry a charge and must go through checkout.
     */
    @Transactional
    fun schedulePlanDowngrade(studioId: StudioId, newPlanKey: PlanKey): StudioEntitlements {
        val preview = previewPlanChange(studioId, newPlanKey)

        when (preview.changeType) {
            ChangeType.NO_CHANGE -> return entitlementService.getEntitlements(studioId)
            ChangeType.UPGRADE -> throw ValidationException(
                "Przejście na droższy pakiet wymaga płatności — użyj POST /api/v1/subscription/checkout (PLAN_UPGRADE)."
            )
            ChangeType.DOWNGRADE -> Unit
        }

        val currentPlanKey = entitlementService.getEntitlements(studioId).planKey
        val effectiveAt = preview.periodEndsAt ?: Instant.now()

        // Replace any existing pending downgrade for this studio.
        pendingPlanChangeRepository.cancelPendingForStudio(studioId.value)
        pendingPlanChangeRepository.save(
            PendingPlanChangeEntity(
                studioId = studioId.value,
                fromPlanKey = currentPlanKey,
                toPlanKey = newPlanKey,
                effectiveAt = effectiveAt
            )
        )

        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.PLAN_DOWNGRADE,
                amountInCents = 0L,
                planKey = newPlanKey,
                description = "Downgrade do planu ${preview.newPlanName} zaplanowany na $effectiveAt"
            )
        )

        logger.info("Studio={} scheduled downgrade from={} to={} effectiveAt={}", studioId, currentPlanKey, newPlanKey, effectiveAt)

        // Current entitlements are unchanged until effectiveAt.
        return entitlementService.getEntitlements(studioId)
    }

    /**
     * Cancels a pending downgrade. The studio keeps its current plan for the full billing period.
     * Returns false if there was no pending downgrade to cancel.
     */
    @Transactional
    fun cancelPendingDowngrade(studioId: StudioId): Boolean {
        val cancelled = pendingPlanChangeRepository.cancelPendingForStudio(studioId.value)
        if (cancelled > 0) logger.info("Studio={} cancelled pending downgrade (user request)", studioId)
        return cancelled > 0
    }

    @Transactional
    fun deactivateAddOnWithLog(studioId: StudioId, addOnKey: AddOnKey) {
        val addOn = addOnRepository.findByKey(addOnKey)
        val currentPlanKey = entitlementService.getEntitlements(studioId).planKey
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.ADD_ON_DEACTIVATION,
                amountInCents = 0,
                planKey = currentPlanKey,
                addOnKey = addOnKey,
                description = "Dezaktywacja modułu ${addOn?.name ?: addOnKey.name}"
            )
        )
        entitlementService.deactivateAddOn(studioId, addOnKey)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun formatCents(cents: Long) = BigDecimal(cents).movePointLeft(2).toPlainString()
}

// ── Result types ──────────────────────────────────────────────────────────────

enum class ChangeType { UPGRADE, DOWNGRADE, NO_CHANGE }

data class PlanChangePreview(
    val changeType: ChangeType,
    val newPlanKey: PlanKey,
    val newPlanName: String,
    val effectiveAt: Instant,
    val proratedAmountCents: Long?,
    val daysRemaining: Long?,
    val periodEndsAt: Instant?,
    val explanation: String
)

data class AddOnActivationPreview(
    val addOnKey: AddOnKey,
    val addOnName: String,
    val proratedAmountCents: Long?,
    val daysRemaining: Long?,
    val periodEndsAt: Instant?,
    val explanation: String
)
