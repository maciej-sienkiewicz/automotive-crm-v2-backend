package pl.detailing.crm.payments.checkout

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.payments.order.PaymentOrderEntity
import pl.detailing.crm.payments.order.PaymentOrderRepository
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.smscampaigns.CommunicationOnboardingService
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import pl.detailing.crm.subscription.management.PendingPlanChangeEntity
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.management.PendingPlanChangeStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class FulfillmentOutcome {
    /** Efekt zastosowany teraz. */
    FULFILLED,
    /** Pieniądze przyjęte, efektu nie da się zastosować — zamówienie czeka na zwrot. */
    REFUND_REQUIRED,
    /** Zamówienie było już rozstrzygnięte (FULFILLED / REFUND_REQUIRED) — duplikat, nic do zrobienia. */
    ALREADY_SETTLED,
    /** Za zamówienie nie ma (jeszcze) pieniędzy. */
    NOT_PAID
}

/** Zamówienie zrealizowane — po commicie uruchamia skutki uboczne niezwiązane z pieniędzmi. */
data class SubscriptionOrderFulfilled(val studioId: StudioId, val orderId: UUID)

/**
 * Applies the business effect of a PAID [PaymentOrderEntity] — dokładnie raz.
 *
 * Idempotencja wynika ze stanu zamówienia odczytanego POD blokadą (studio → zamówienie):
 * PAID → efekt → FULFILLED w jednej transakcji, a druga obsługa tej samej płatności czeka na
 * blokadę i widzi FULFILLED. Wcześniej „czy już PAID?" sprawdzano bez blokady i bez wersji,
 * więc równoległy duplikat notyfikacji przedłużał abonament dwa razy (audyt, P1 — odtworzone:
 * +60 dni). Ostatnią linią obrony jest unikat (order_id, event_type) w historii płatności.
 *
 * Failure semantics: wyjątek cofa TYLKO realizację — zamówienie zostaje PAID (fakt otrzymania
 * pieniędzy zapisano wcześniej, osobno), a realizację ponawia `PaymentReconciliationJob`.
 * Dawniej błąd cofał PAID do PENDING i fakt płatności znikał z bazy (audyt, P6).
 *
 * Gdy efektu nie da się zastosować (moduł już aktywny, plan już zmieniony, studio opłacone
 * innym zamówieniem), zamówienie przechodzi w REFUND_REQUIRED z powodem, licznikiem
 * `payments.refund.required` i logiem ERROR — zamiast przepadać po cichu (audyt, P2c, P7).
 */
@Service
class OrderFulfillmentService(
    private val studioRepository: StudioRepository,
    private val orderRepository: PaymentOrderRepository,
    private val entitlementService: EntitlementService,
    private val planRepository: PlanJpaRepository,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val accessPolicy: SubscriptionAccessPolicy,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private sealed interface Effect {
        data class Applied(val eventType: SubscriptionEventType) : Effect
        data class NotApplicable(val reason: String) : Effect
    }

    @Transactional
    fun fulfillIfPaid(orderId: UUID): FulfillmentOutcome {
        val studioId = orderRepository.findStudioIdById(orderId)
            ?: throw EntityNotFoundException("Zamówienie nie zostało znalezione: $orderId")
        // Kolejność blokad jak w całym module: studio → (plan, moduły) → zamówienie.
        val studio = studioRepository.lockById(studioId)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
        val order = orderRepository.lockById(orderId)
            ?: throw EntityNotFoundException("Zamówienie nie zostało znalezione: $orderId")

        when (order.status) {
            PaymentOrderStatus.FULFILLED, PaymentOrderStatus.REFUND_REQUIRED -> return FulfillmentOutcome.ALREADY_SETTLED
            PaymentOrderStatus.PAID -> Unit
            else -> return FulfillmentOutcome.NOT_PAID
        }

        val now = accessPolicy.now()
        val effect = try {
            applyEffect(order, studio)
        } catch (ex: Exception) {
            meterRegistry.counter("subscription.fulfillment.failures", "orderType", order.type.name).increment()
            logger.error(
                "Fulfillment FAILED orderId={} type={} studio={} amountCents={} — zamówienie zostaje PAID, " +
                "realizację ponowi worker rekoncyliacji.",
                order.id, order.type, studioId, order.amountCents, ex
            )
            throw ex
        }

        val outcome = when (effect) {
            is Effect.Applied -> {
                order.markFulfilled(now)
                ledger(order, effect.eventType)
                eventPublisher.publishEvent(SubscriptionOrderFulfilled(StudioId(studioId), order.id))
                logger.info("Order fulfilled id={} type={} studio={}", order.id, order.type, studioId)
                FulfillmentOutcome.FULFILLED
            }
            is Effect.NotApplicable -> {
                order.markRefundRequired(effect.reason, now)
                meterRegistry.counter("payments.refund.required", "orderType", order.type.name).increment()
                logger.error(
                    "REFUND REQUIRED orderId={} type={} studio={} amountCents={} p24OrderId={}: {}",
                    order.id, order.type, studioId, order.amountCents, order.p24OrderId, effect.reason
                )
                FulfillmentOutcome.REFUND_REQUIRED
            }
        }
        entitlementService.evictEntitlementsCache(StudioId(studioId))
        return outcome
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun applyEffect(order: PaymentOrderEntity, studio: StudioEntity): Effect = when (order.type) {
        PaymentOrderType.INITIAL_PURCHASE -> applyInitialPurchase(order, studio)
        PaymentOrderType.RENEWAL -> applyRenewal(order, studio)
        PaymentOrderType.PLAN_UPGRADE -> applyPlanUpgrade(order, studio)
        PaymentOrderType.ADD_ON_PURCHASE -> applyAddOnPurchase(order, studio)
    }

    private fun applyInitialPurchase(order: PaymentOrderEntity, studio: StudioEntity): Effect {
        val planKey = requireNotNull(order.planKey) { "INITIAL_PURCHASE order without planKey: ${order.id}" }
        val billing = studio.billing()
        val paidAt = order.paidAt!!
        // Opłacone studio (np. drugim zamówieniem w międzyczasie) nie kupuje „startu" drugi raz.
        if ((studio.subscriptionStatus == SubscriptionStatus.ACTIVE || studio.subscriptionStatus == SubscriptionStatus.PAST_DUE) &&
            accessPolicy.isAccessible(billing, paidAt)
        ) {
            return Effect.NotApplicable("Studio ma już opłaconą subskrypcję — zamówienie startowe opłacone ponownie")
        }

        startPaidPeriod(studio, accessPolicy.paidPeriodStart(billing, paidAt))
        val studioId = StudioId(studio.id)
        pendingPlanChangeRepository.cancelPendingForStudio(studio.id)
        // Zmiana planu czyści moduły; zakup definiuje zestaw od nowa: plan + moduły z zamówienia.
        entitlementService.changePlan(studioId, planKey)
        order.addOnKeys.forEach { entitlementService.activateAddOn(studioId, it) }
        return Effect.Applied(SubscriptionEventType.SUBSCRIPTION_PURCHASE)
    }

    /**
     * Odnowienie kupuje KOLEJNY okres planu z zamówienia (cena liczona przez
     * [pl.detailing.crm.subscription.pricing.PricingService.nextPeriodPrice]). Jeśli między
     * zamówieniem a płatnością właściciel zmienił plany na kolejny okres, wygrywa to, za co
     * zapłacił: zapłacił za wyższy — zaplanowany downgrade jest odwoływany; za niższy —
     * downgrade planuje się na początek opłaconego okresu (audyt, S1).
     */
    private fun applyRenewal(order: PaymentOrderEntity, studio: StudioEntity): Effect {
        if (studio.subscriptionStatus == SubscriptionStatus.NO_PLAN) {
            return Effect.NotApplicable("Studio nie ma pakietu do przedłużenia")
        }
        val studioId = StudioId(studio.id)
        val periodStart = accessPolicy.paidPeriodStart(studio.billing(), order.paidAt!!)
        startPaidPeriod(studio, periodStart)
        order.planKey?.let { alignNextPeriodPlan(studioId, it, periodStart) }
        return Effect.Applied(SubscriptionEventType.SUBSCRIPTION_RENEWAL)
    }

    private fun applyPlanUpgrade(order: PaymentOrderEntity, studio: StudioEntity): Effect {
        val planKey = requireNotNull(order.planKey) { "PLAN_UPGRADE order without planKey: ${order.id}" }
        val studioId = StudioId(studio.id)
        val current = entitlementService.readCurrent(studioId)
        val currentPlan = planRepository.findByKey(current.planKey)
        val targetPlan = planRepository.findByKey(planKey)
            ?: throw EntityNotFoundException("Pakiet nie został znaleziony: $planKey")
        if (currentPlan != null && currentPlan.monthlyPriceGrossCents >= targetPlan.monthlyPriceGrossCents) {
            return Effect.NotApplicable("Studio ma już plan ${currentPlan.name} — upgrade do ${targetPlan.name} nie zmienia niczego")
        }

        // The buyer changed their mind — a paid upgrade supersedes any scheduled downgrade.
        pendingPlanChangeRepository.cancelPendingForStudio(studio.id)
        entitlementService.changePlan(studioId, planKey)
        return Effect.Applied(SubscriptionEventType.PLAN_UPGRADE)
    }

    private fun applyAddOnPurchase(order: PaymentOrderEntity, studio: StudioEntity): Effect {
        val studioId = StudioId(studio.id)
        // Self-heal instead of exploding after the money is captured: checkout already
        // validates the plan row exists, but if provisioning drifted between checkout
        // and webhook, re-establish the invariant and deliver what was paid for.
        entitlementService.ensurePlanAssigned(studioId, order.planKey ?: PlanKey.BASIC)

        val current = entitlementService.readCurrent(studioId)
        if (current.planKey == PlanKey.FULL) {
            return Effect.NotApplicable("Studio ma pakiet ${current.planName}, który zawiera już wszystkie moduły")
        }
        val alreadyActive = order.addOnKeys.filter { it in current.activeAddOnKeys }
        if (alreadyActive.isNotEmpty()) {
            return Effect.NotApplicable("Moduł ${alreadyActive.joinToString { it.displayName }} był już aktywny — opłacony drugi raz")
        }
        order.addOnKeys.forEach { entitlementService.activateAddOn(studioId, it) }
        return Effect.Applied(SubscriptionEventType.ADD_ON_ACTIVATION)
    }

    private fun startPaidPeriod(studio: StudioEntity, periodStart: Instant) {
        studio.subscriptionStatus = SubscriptionStatus.ACTIVE
        studio.subscriptionEndsAt = periodStart.plus(BILLING_PERIOD_DAYS, ChronoUnit.DAYS)
        studio.trialEndsAt = null
        studio.graceEndsAt = null
    }

    /** Plan kolejnego okresu = plan, za który zapłacono w odnowieniu. */
    private fun alignNextPeriodPlan(studioId: StudioId, paidPlanKey: PlanKey, periodStart: Instant) {
        val currentPlanKey = entitlementService.readCurrent(studioId).planKey
        val pending = pendingPlanChangeRepository.findByStudioIdAndStatus(studioId.value, PendingPlanChangeStatus.PENDING)
        val nextPlanKey = pending?.toPlanKey ?: currentPlanKey
        if (nextPlanKey == paidPlanKey) return

        pendingPlanChangeRepository.cancelPendingForStudio(studioId.value)
        if (paidPlanKey == currentPlanKey) {
            logger.info("Studio={} paid renewal at {} — pending downgrade to {} cancelled", studioId, paidPlanKey, pending?.toPlanKey)
            return
        }
        if (!periodStart.isAfter(accessPolicy.now())) {
            entitlementService.changePlan(studioId, paidPlanKey)
        } else {
            pendingPlanChangeRepository.save(
                PendingPlanChangeEntity(
                    studioId = studioId.value,
                    fromPlanKey = currentPlanKey,
                    toPlanKey = paidPlanKey,
                    effectiveAt = periodStart,
                    requestedAt = accessPolicy.now()
                )
            )
        }
        logger.info("Studio={} renewal paid for plan {} — next period aligned (from {})", studioId, paidPlanKey, currentPlanKey)
    }

    private fun ledger(order: PaymentOrderEntity, eventType: SubscriptionEventType) {
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = order.studioId,
                eventType = eventType,
                amountInCents = order.amountCents,
                currency = order.currency,
                transactionId = order.p24OrderId?.toString() ?: order.sessionId,
                planKey = order.planKey,
                addOnKey = order.addOnKeys.singleOrNull()?.name,
                description = order.description,
                orderId = order.id
            )
        )
    }

    companion object {
        const val BILLING_PERIOD_DAYS = 30L
    }
}

/**
 * Skutki uboczne realizacji, które nie mogą jej wywrócić: onboarding modułu komunikacji
 * (szablony, kredyty startowe). Dawniej biegł w transakcji realizacji — jego błąd cofał
 * aktywację opłaconego zamówienia. Teraz po commicie, we własnej transakcji, a błąd jest
 * tylko logowany (serwis onboardingu jest idempotentny, kolejne zamówienie go dokończy).
 */
@Component
class SubscriptionFulfillmentSideEffects(
    private val entitlementService: EntitlementService,
    private val communicationOnboardingService: CommunicationOnboardingService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onFulfilled(event: SubscriptionOrderFulfilled) {
        try {
            val entitlements = entitlementService.getEntitlements(event.studioId)
            if (!entitlements.hasFeature(FeatureKey.SMS_EMAIL)) return
            communicationOnboardingService.onCommunicationEnabled(event.studioId, entitlements.planKey)
        } catch (e: Exception) {
            logger.error("Onboarding komunikacji po zamówieniu {} studia {} nie powiódł się", event.orderId, event.studioId, e)
        }
    }
}
