package pl.detailing.crm.subscription.management

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscredits.payment.MockPaymentGateway
import pl.detailing.crm.smscredits.payment.PaymentRequest
import pl.detailing.crm.studio.infrastructure.StudioRepository
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
import java.time.temporal.ChronoUnit

/**
 * Orchestrates plan changes and add-on activations, combining:
 * 1. Proration calculation ([ProrationService])
 * 2. Payment charging ([MockPaymentGateway])
 * 3. Entitlement assignment ([EntitlementService])
 *
 * Plan change semantics:
 *   UPGRADE (more expensive) → immediate, prorated charge for the price difference
 *   DOWNGRADE (less expensive) → persisted in [PendingPlanChangeEntity], applied by
 *                                 [PlanDowngradeScheduler] at [effectiveAt] (= period end)
 *   SAME PLAN                  → no-op
 *
 * Add-on activation:
 *   Always immediate. Prorated charge for remaining days in the billing period.
 *   On trial (no subscriptionEndsAt): free activation, full price charged on next renewal.
 */
@Service
class PlanManagementService(
    private val entitlementService: EntitlementService,
    private val prorationService: ProrationService,
    private val paymentGateway: MockPaymentGateway,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val studioRepository: StudioRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ── Plan change ───────────────────────────────────────────────────────────

    /**
     * Previews the cost and timing of switching to [newPlanKey].
     * Does NOT make any changes — safe to call from a confirmation dialog.
     */
    fun previewPlanChange(studioId: StudioId, newPlanKey: PlanKey): PlanChangePreview {
        val current = entitlementService.getEntitlements(studioId)
        val currentPlan = planRepository.findByKey(current.planKey)
            ?: throw EntityNotFoundException("Bieżący plan nie został znaleziony: ${current.planKey}")
        val newPlan = planRepository.findByKey(newPlanKey)
            ?: throw EntityNotFoundException("Plan not found: $newPlanKey")

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
                val periodEndsAt = prorationService.calculateAddOnActivation(studioId, 0)?.periodEndsAt
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
     * Does NOT make any changes.
     */
    fun previewAddOnActivation(studioId: StudioId, addOnKey: AddOnKey): AddOnActivationPreview {
        val addOn = addOnRepository.findByKey(addOnKey)
            ?: throw EntityNotFoundException("Add-on not found: $addOnKey")

        if (!addOn.isAvailable) {
            return AddOnActivationPreview(
                addOnKey = addOnKey,
                addOnName = addOn.name,
                proratedAmountCents = null,
                daysRemaining = null,
                periodEndsAt = null,
                explanation = "Ten moduł jest jeszcze w przygotowaniu i nie można go aktywować."
            )
        }

        if (addOn.monthlyPriceGrossCents == null) {
            return AddOnActivationPreview(
                addOnKey = addOnKey,
                addOnName = addOn.name,
                proratedAmountCents = null,
                daysRemaining = null,
                periodEndsAt = null,
                explanation = "Cena tego modułu jest jeszcze ustalana. Skontaktuj się z nami."
            )
        }

        val proration = prorationService.calculateAddOnActivation(studioId, addOn.monthlyPriceGrossCents)

        return AddOnActivationPreview(
            addOnKey = addOnKey,
            addOnName = addOn.name,
            proratedAmountCents = proration?.proratedAmountCents,
            daysRemaining = proration?.daysRemaining,
            periodEndsAt = proration?.periodEndsAt,
            explanation = if (proration != null)
                "Zostaniesz obciążony kwotą proporcjonalną za ${proration.daysRemaining} dni pozostałych w bieżącym okresie rozliczeniowym. Od następnego odnowienia pełna cena ${formatCents(addOn.monthlyPriceGrossCents)} PLN/mies."
            else
                "Aktywacja bezpłatna w ramach okresu próbnego. Pełna cena ${formatCents(addOn.monthlyPriceGrossCents)} PLN/mies. zostanie naliczona od pierwszego odnowienia."
        )
    }

    /** Returns the PENDING downgrade for the studio, if any. */
    fun getPendingDowngrade(studioId: StudioId): PendingPlanChangeEntity? =
        pendingPlanChangeRepository.findByStudioIdAndStatus(studioId.value, PendingPlanChangeStatus.PENDING)

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Executes a plan change with appropriate billing.
     *
     * UPGRADE: immediate activation + prorated charge for the price difference.
     *   Any existing pending downgrade is cancelled (user changed their mind).
     *
     * DOWNGRADE: persists a [PendingPlanChangeEntity] row; the current plan remains
     *   active until [PlanDowngradeScheduler] processes it at [effectiveAt].
     *   Only one PENDING downgrade per studio is allowed — subsequent requests
     *   replace the previous one.
     *
     * SAME PLAN: no-op.
     */
    @Transactional
    fun changePlan(studioId: StudioId, newPlanKey: PlanKey): StudioEntitlements {
        val studio = studioRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")

        // First-time plan selection: studio has no active subscription yet.
        // Treat as an initial purchase — charge full first month, activate the studio.
        if (studio.subscriptionStatus == SubscriptionStatus.NO_PLAN ||
            studio.subscriptionStatus == SubscriptionStatus.EXPIRED) {
            return activateInitialPlan(studioId, newPlanKey)
        }

        val preview = previewPlanChange(studioId, newPlanKey)

        if (preview.changeType == ChangeType.NO_CHANGE) {
            return entitlementService.getEntitlements(studioId)
        }

        if (preview.changeType == ChangeType.UPGRADE) {
            // Cancel any pending downgrade — user is upgrading instead.
            val cancelled = pendingPlanChangeRepository.cancelPendingForStudio(studioId.value)
            if (cancelled > 0) logger.info("Studio={} cancelled pending downgrade due to upgrade", studioId)

            var transactionId: String? = null
            if (preview.proratedAmountCents != null && preview.proratedAmountCents > 0) {
                val result = paymentGateway.charge(
                    PaymentRequest(
                        amountInCents = preview.proratedAmountCents,
                        currency = "PLN",
                        description = "Zmiana planu na ${preview.newPlanName} — ${preview.daysRemaining} dni",
                        studioId = studioId.value
                    )
                )
                if (!result.success) throw ValidationException("Płatność nie powiodła się: ${result.message}")
                transactionId = result.transactionId
                logger.info("Studio={} upgraded to plan={} proratedCents={} txId={}", studioId, newPlanKey, preview.proratedAmountCents, transactionId)
            }

            paymentLogRepository.save(
                SubscriptionPaymentLogEntity(
                    studioId = studioId.value,
                    eventType = SubscriptionEventType.PLAN_UPGRADE,
                    amountInCents = preview.proratedAmountCents ?: 0L,
                    transactionId = transactionId,
                    planKey = newPlanKey,
                    description = "Upgrade do planu ${preview.newPlanName} — ${preview.daysRemaining} dni (proporcjonalnie)"
                )
            )

            return entitlementService.assignPlan(studioId, newPlanKey)
        }

        // DOWNGRADE — defer to period end.
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
     * Initial plan purchase for studios that have never had a subscription (NO_PLAN or EXPIRED).
     * Charges the full monthly price, sets the studio ACTIVE for 30 days, assigns the feature plan.
     */
    private fun activateInitialPlan(studioId: StudioId, planKey: PlanKey): StudioEntitlements {
        val plan = planRepository.findByKey(planKey)
            ?: throw EntityNotFoundException("Plan not found: $planKey")

        val result = paymentGateway.charge(
            PaymentRequest(
                amountInCents = plan.monthlyPriceGrossCents,
                currency = "PLN",
                description = "Aktywacja planu ${plan.name} — pierwszy miesiąc",
                studioId = studioId.value
            )
        )
        if (!result.success) throw ValidationException("Płatność nie powiodła się: ${result.message}")

        val subscriptionEndsAt = Instant.now().plus(30, ChronoUnit.DAYS)
        val studioEntity = studioRepository.findByStudioId(studioId.value)!!
        studioEntity.subscriptionStatus = SubscriptionStatus.ACTIVE
        studioEntity.subscriptionEndsAt = subscriptionEndsAt
        studioRepository.save(studioEntity)

        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.SUBSCRIPTION_PURCHASE,
                amountInCents = plan.monthlyPriceGrossCents,
                currency = "PLN",
                transactionId = result.transactionId,
                planKey = planKey,
                description = "Aktywacja planu ${plan.name} — pierwszy miesiąc (do $subscriptionEndsAt)"
            )
        )

        logger.info(
            "Studio={} activated initial plan={} txId={} endsAt={}",
            studioId, planKey, result.transactionId, subscriptionEndsAt
        )

        return entitlementService.assignPlan(studioId, planKey)
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

    /**
     * Activates an add-on with prorated billing.
     */
    @Transactional
    fun activateAddOnWithBilling(studioId: StudioId, addOnKey: AddOnKey): StudioEntitlements {
        val addOn = addOnRepository.findByKey(addOnKey)
            ?: throw EntityNotFoundException("Add-on not found: $addOnKey")

        if (!addOn.isAvailable) throw ValidationException("Moduł '${addOn.name}' nie jest jeszcze dostępny.")
        if (addOn.monthlyPriceGrossCents == null) throw ValidationException("Moduł '${addOn.name}' nie ma jeszcze ustalonej ceny.")

        val proration = prorationService.calculateAddOnActivation(studioId, addOn.monthlyPriceGrossCents)
        var transactionId: String? = null

        if (proration != null && proration.proratedAmountCents > 0) {
            val result = paymentGateway.charge(
                PaymentRequest(
                    amountInCents = proration.proratedAmountCents,
                    currency = proration.currency,
                    description = "Aktywacja modułu ${addOn.name} — ${proration.daysRemaining} dni",
                    studioId = studioId.value
                )
            )
            if (!result.success) throw ValidationException("Płatność nie powiodła się: ${result.message}")
            transactionId = result.transactionId
            logger.info("Studio={} activated add-on={} proratedCents={} txId={}", studioId, addOnKey, proration.proratedAmountCents, transactionId)
        }

        val currentPlanKey = entitlementService.getEntitlements(studioId).planKey
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.ADD_ON_ACTIVATION,
                amountInCents = proration?.proratedAmountCents ?: 0L,
                transactionId = transactionId,
                planKey = currentPlanKey,
                addOnKey = addOnKey,
                description = if (proration != null)
                    "Aktywacja modułu ${addOn.name} — ${proration.daysRemaining} dni (proporcjonalnie)"
                else
                    "Aktywacja modułu ${addOn.name} — bezpłatnie w trakcie okresu próbnego"
            )
        )

        return entitlementService.activateAddOn(studioId, addOnKey)
    }

    /**
     * First-purchase bundle: activates the base plan and all selected add-ons in one transaction.
     * Only valid when the studio has no active subscription (NO_PLAN or EXPIRED).
     * All add-ons are validated before any payment is attempted to minimise partial-activation risk.
     */
    @Transactional
    fun activatePackage(studioId: StudioId, planKey: PlanKey, addOnKeys: List<AddOnKey>): StudioEntitlements {
        val studio = studioRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")

        if (studio.subscriptionStatus != SubscriptionStatus.NO_PLAN &&
            studio.subscriptionStatus != SubscriptionStatus.EXPIRED) {
            throw ValidationException("Studio ma już aktywną subskrypcję. Użyj change-plan lub activate-add-on.")
        }

        val addOns = addOnKeys.map { key ->
            addOnRepository.findByKey(key)
                ?: throw EntityNotFoundException("Moduł nie istnieje: $key")
        }
        addOns.forEach { addOn ->
            if (!addOn.isAvailable) throw ValidationException("Moduł '${addOn.name}' nie jest jeszcze dostępny.")
            if (addOn.monthlyPriceGrossCents == null) throw ValidationException("Moduł '${addOn.name}' nie ma jeszcze ustalonej ceny.")
        }

        activateInitialPlan(studioId, planKey)
        addOnKeys.forEach { addOnKey -> activateAddOnWithBilling(studioId, addOnKey) }

        return entitlementService.getEntitlements(studioId)
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
