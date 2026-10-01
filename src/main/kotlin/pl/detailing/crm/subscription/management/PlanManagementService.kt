package pl.detailing.crm.subscription.management

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionConflictException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.AddOnCancellationResult
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.lifecycle.BillingDates
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import pl.detailing.crm.subscription.pricing.MidPeriodPurchaseMode
import pl.detailing.crm.subscription.pricing.PaidAddOnCredit
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
 *   DOWNGRADE  → na koniec trwającego opłaconego okresu ([PendingPlanChangeEntity],
 *                stosuje [PlanDowngradeScheduler]); bez trwającego okresu — od razu
 *   CANCEL     → withdraws a pending downgrade (dopóki kolejny okres nie jest opłacony)
 *   ADD-ON OFF → z końcem opłaconego okresu (moduł opłacony działa do końca), w trialu od razu
 *   RESUME     → cofa zaplanowane wyłączenie modułu
 *
 * Każda mutacja bierze blokadę wiersza studia jako pierwszą — tę samą, którą biorą realizacja
 * zamówień i joby, więc anulowanie i zastosowanie downgrade'u nie mogą się już wyprzedzić.
 */
@Service
class PlanManagementService(
    private val entitlementService: EntitlementService,
    private val prorationService: ProrationService,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val studioRepository: StudioRepository,
    private val accessPolicy: SubscriptionAccessPolicy
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
        val now = accessPolicy.now()

        return when {
            newPlan.monthlyPriceGrossCents > currentPlan.monthlyPriceGrossCents -> when (prorationService.midPeriodPurchaseMode(studioId)) {
                MidPeriodPurchaseMode.TRIAL_FREE -> PlanChangePreview(
                    changeType = ChangeType.UPGRADE, newPlanKey = newPlanKey, newPlanName = newPlan.name,
                    effectiveAt = now, proratedAmountCents = null, daysRemaining = null, periodEndsAt = null,
                    explanation = "Plan zostanie aktywowany natychmiast (jesteś na okresie próbnym — pełna cena od pierwszego zakupu)."
                )
                MidPeriodPurchaseMode.PRORATED -> {
                    val proration = prorationService.calculatePlanUpgrade(
                        studioId, currentPlan.monthlyPriceGrossCents, newPlan.monthlyPriceGrossCents, paidAddOnCredits(current)
                    )!!
                    PlanChangePreview(
                        changeType = ChangeType.UPGRADE, newPlanKey = newPlanKey, newPlanName = newPlan.name,
                        effectiveAt = now, proratedAmountCents = proration.proratedAmountCents,
                        daysRemaining = proration.daysRemaining, periodEndsAt = proration.periodEndsAt,
                        explanation = "Zapłacisz proporcjonalnie za ${proration.daysRemaining} dni do końca okresu rozliczeniowego" +
                            (if (current.activeAddOnKeys.isNotEmpty()) ", z zaliczeniem opłaconych modułów, które plan ${newPlan.name} już zawiera." else ".")
                    )
                }
                MidPeriodPurchaseMode.NOT_ALLOWED -> PlanChangePreview(
                    changeType = ChangeType.UPGRADE, newPlanKey = newPlanKey, newPlanName = newPlan.name,
                    effectiveAt = now, proratedAmountCents = null, daysRemaining = null, periodEndsAt = null,
                    explanation = NOT_ALLOWED_EXPLANATION, allowed = false
                )
            }

            newPlan.monthlyPriceGrossCents < currentPlan.monthlyPriceGrossCents -> {
                val periodEndsAt = prorationService.runningPeriodEnd(studioId)
                PlanChangePreview(
                    changeType = ChangeType.DOWNGRADE,
                    newPlanKey = newPlanKey,
                    newPlanName = newPlan.name,
                    effectiveAt = periodEndsAt ?: now,
                    proratedAmountCents = null,
                    daysRemaining = prorationService.daysRemainingInPeriod(studioId),
                    periodEndsAt = periodEndsAt,
                    explanation = if (periodEndsAt != null)
                        "Zmiana wejdzie w życie po zakończeniu bieżącego okresu rozliczeniowego (${BillingDates.format(periodEndsAt)}). Do tego czasu zachowujesz obecny plan, a kolejny okres opłacisz już w cenie planu ${newPlan.name}."
                    else
                        "Plan zostanie zmieniony natychmiast."
                )
            }

            else -> PlanChangePreview(
                changeType = ChangeType.NO_CHANGE,
                newPlanKey = newPlanKey,
                newPlanName = newPlan.name,
                effectiveAt = now,
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
                addOnKey = addOnKey, addOnName = addOn.name, proratedAmountCents = null,
                daysRemaining = null, periodEndsAt = null,
                explanation = "Ten moduł jest jeszcze w przygotowaniu i nie można go aktywować.", allowed = false
            )
        }
        val price = addOn.monthlyPriceGrossCents!!

        return when (prorationService.midPeriodPurchaseMode(studioId)) {
            MidPeriodPurchaseMode.TRIAL_FREE -> AddOnActivationPreview(
                addOnKey = addOnKey, addOnName = addOn.name, proratedAmountCents = null,
                daysRemaining = null, periodEndsAt = null,
                explanation = "Aktywacja bezpłatna w ramach okresu próbnego. Pełna cena ${formatCents(price)} PLN/mies. od pierwszego zakupu okresu."
            )
            MidPeriodPurchaseMode.PRORATED -> {
                val proration = prorationService.calculateAddOnActivation(studioId, price)!!
                AddOnActivationPreview(
                    addOnKey = addOnKey, addOnName = addOn.name, proratedAmountCents = proration.proratedAmountCents,
                    daysRemaining = proration.daysRemaining, periodEndsAt = proration.periodEndsAt,
                    explanation = "Zapłacisz proporcjonalnie za ${proration.daysRemaining} dni pozostałych w bieżącym okresie rozliczeniowym. Od następnego odnowienia pełna cena ${formatCents(price)} PLN/mies."
                )
            }
            MidPeriodPurchaseMode.NOT_ALLOWED -> AddOnActivationPreview(
                addOnKey = addOnKey, addOnName = addOn.name, proratedAmountCents = null,
                daysRemaining = null, periodEndsAt = null, explanation = NOT_ALLOWED_EXPLANATION, allowed = false
            )
        }
    }

    /** Returns the PENDING downgrade for the studio, if any. */
    fun getPendingDowngrade(studioId: StudioId): PendingPlanChangeEntity? =
        pendingPlanChangeRepository.findByStudioIdAndStatus(studioId.value, PendingPlanChangeStatus.PENDING)

    /**
     * Czy zaplanowany downgrade da się jeszcze odwołać: nie, gdy kolejny okres (zaczynający
     * się w chwili downgrade'u) jest już opłacony — w cenie niższego planu.
     */
    fun isCancellable(studio: StudioEntity, pending: PendingPlanChangeEntity): Boolean =
        studio.subscriptionEndsAt?.isAfter(pending.effectiveAt) != true

    // ── Free mutations ────────────────────────────────────────────────────────

    /**
     * Downgrade do tańszego planu. Upgrades are rejected here — they carry a charge and
     * must go through checkout.
     *
     * Trwa opłacony okres → zmiana czeka do jego końca. Nie trwa (trial, karencja,
     * wygasłe) → zmiana od razu: nie ma czego „dokończyć", a odnowienie i tak policzy już
     * cenę planu docelowego.
     */
    @Transactional
    fun schedulePlanDowngrade(studioId: StudioId, newPlanKey: PlanKey): StudioEntitlements {
        studioRepository.lockById(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
        val preview = previewPlanChange(studioId, newPlanKey)

        when (preview.changeType) {
            ChangeType.NO_CHANGE -> return entitlementService.getEntitlements(studioId)
            ChangeType.UPGRADE -> throw ValidationException(
                "Przejście na droższy pakiet wymaga płatności — użyj POST /api/v1/subscription/checkout (PLAN_UPGRADE)."
            )
            ChangeType.DOWNGRADE -> Unit
        }

        val currentPlanKey = entitlementService.getEntitlements(studioId).planKey
        // Zastępuje wcześniejszy plan zmiany; częściowy unikat pilnuje, że PENDING jest jeden.
        pendingPlanChangeRepository.cancelPendingForStudio(studioId.value)

        val periodEndsAt = preview.periodEndsAt
        if (periodEndsAt == null) {
            entitlementService.changePlan(studioId, newPlanKey)
            log(studioId, newPlanKey, "Zmiana planu z ${currentPlanKey.displayName} na ${preview.newPlanName} — zastosowana od razu (brak trwającego opłaconego okresu)")
            logger.info("Studio={} downgraded immediately from={} to={}", studioId, currentPlanKey, newPlanKey)
        } else {
            pendingPlanChangeRepository.save(
                PendingPlanChangeEntity(
                    studioId = studioId.value,
                    fromPlanKey = currentPlanKey,
                    toPlanKey = newPlanKey,
                    effectiveAt = periodEndsAt,
                    requestedAt = accessPolicy.now()
                )
            )
            log(studioId, newPlanKey, "Downgrade do planu ${preview.newPlanName} zaplanowany na ${BillingDates.format(periodEndsAt)}")
            logger.info("Studio={} scheduled downgrade from={} to={} effectiveAt={}", studioId, currentPlanKey, newPlanKey, periodEndsAt)
        }
        return entitlementService.getEntitlements(studioId)
    }

    /**
     * Cancels a pending downgrade. The studio keeps its current plan for the full billing period.
     * Returns false if there was no pending downgrade to cancel (np. scheduler już go zastosował —
     * decyzja zapada pod blokadą studia, więc odpowiedź „anulowano" jest wiążąca, audyt S2).
     *
     * @throws SubscriptionConflictException `DOWNGRADE_ALREADY_PAID`, gdy kolejny okres jest już
     *   opłacony w cenie niższego planu — odwołanie dałoby wyższy plan za niższą cenę.
     */
    @Transactional
    fun cancelPendingDowngrade(studioId: StudioId): Boolean {
        val studio = studioRepository.lockById(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
        val pending = getPendingDowngrade(studioId) ?: return false

        if (!isCancellable(studio, pending)) {
            throw SubscriptionConflictException(
                code = "DOWNGRADE_ALREADY_PAID",
                message = "Kolejny okres jest już opłacony w planie ${pending.toPlanKey.displayName}, więc tej zmiany nie można odwołać. " +
                    "Po jej wejściu w życie możesz przejść na wyższy plan z dopłatą proporcjonalną."
            )
        }

        pending.status = PendingPlanChangeStatus.CANCELLED
        logger.info("Studio={} cancelled pending downgrade (user request)", studioId)
        return true
    }

    /**
     * Wyłącza moduł: z końcem trwającego opłaconego okresu (moduł działa do końca, nie wchodzi
     * do ceny odnowienia), a bez trwającego okresu — od razu. Dawniej zawsze od razu, bez
     * zwrotu, choć moduł był opłacony do końca okresu (audyt, S6).
     */
    @Transactional
    fun cancelAddOn(studioId: StudioId, addOnKey: AddOnKey): AddOnCancellationResult {
        studioRepository.lockById(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
        val addOn = addOnRepository.findByKey(addOnKey)
        val cancelAt = prorationService.runningPeriodEnd(studioId)
        val currentPlanKey = entitlementService.getEntitlements(studioId).planKey

        val result = entitlementService.cancelAddOn(studioId, addOnKey, cancelAt)
        if (result == AddOnCancellationResult.NOT_ACTIVE) return result

        val description = when (result) {
            AddOnCancellationResult.SCHEDULED -> "Wyłączenie modułu ${addOn?.name ?: addOnKey.name} z końcem okresu (${cancelAt?.let(BillingDates::format)})"
            else -> "Dezaktywacja modułu ${addOn?.name ?: addOnKey.name}"
        }
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.ADD_ON_DEACTIVATION,
                amountInCents = 0,
                planKey = currentPlanKey,
                addOnKey = addOnKey.name,
                description = description
            )
        )
        return result
    }

    /** Cofa zaplanowane wyłączenie modułu (bez opłaty — moduł jest opłacony do końca okresu). */
    @Transactional
    fun resumeAddOn(studioId: StudioId, addOnKey: AddOnKey) {
        studioRepository.lockById(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
        if (!entitlementService.resumeAddOn(studioId, addOnKey)) {
            throw EntityNotFoundException("Moduł nie jest aktywny: $addOnKey")
        }
        val entitlements = entitlementService.getEntitlements(studioId)
        val addOn = addOnRepository.findByKey(addOnKey)
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.ADD_ON_ACTIVATION,
                amountInCents = 0,
                planKey = entitlements.planKey,
                addOnKey = addOnKey.name,
                description = "Cofnięto zaplanowane wyłączenie modułu ${addOn?.name ?: addOnKey.name}"
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Moduły opłacone w bieżącym okresie — do zaliczenia przy upgradzie. */
    internal fun paidAddOnCredits(entitlements: StudioEntitlements): List<PaidAddOnCredit> {
        if (entitlements.activeAddOnKeys.isEmpty()) return emptyList()
        return addOnRepository.findAllByKeyIn(entitlements.activeAddOnKeys).mapNotNull { addOn ->
            addOn.monthlyPriceGrossCents?.let { PaidAddOnCredit(it, entitlements.addOnCancellations[addOn.key]) }
        }
    }

    private fun log(studioId: StudioId, planKey: PlanKey, description: String) {
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = studioId.value,
                eventType = SubscriptionEventType.PLAN_DOWNGRADE,
                amountInCents = 0L,
                planKey = planKey,
                description = description
            )
        )
    }

    private fun formatCents(cents: Long) = BigDecimal(cents).movePointLeft(2).toPlainString()

    companion object {
        const val NOT_ALLOWED_EXPLANATION =
            "Okres rozliczeniowy nie trwa — najpierw opłać przedłużenie subskrypcji, potem zmienisz plan lub dokupisz moduł."
    }
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
    val explanation: String,
    /** False, gdy zmiany nie da się teraz kupić (brak trwającego okresu). */
    val allowed: Boolean = true
)

data class AddOnActivationPreview(
    val addOnKey: AddOnKey,
    val addOnName: String,
    val proratedAmountCents: Long?,
    val daysRemaining: Long?,
    val periodEndsAt: Instant?,
    val explanation: String,
    val allowed: Boolean = true
)
