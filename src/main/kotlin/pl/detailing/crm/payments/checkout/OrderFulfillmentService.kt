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
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import pl.detailing.crm.subscription.lifecycle.BillingDates
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
    NOT_PAID,
    /** Darmowe zamówienie (trial), którego efektu nie da się zastosować — anulowane, nie ma czego zwracać. */
    CANCELLED
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
            is Effect.NotApplicable -> if (order.amountCents == 0L) {
                order.cancelFreeOrder(effect.reason)
                logger.warn("Free order {} type={} studio={} cancelled: {}", order.id, order.type, studioId, effect.reason)
                FulfillmentOutcome.CANCELLED
            } else {
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
     * Odnowienie kupuje KOLEJNY okres — dokładnie ten plan i te moduły, które były w zamówieniu
     * (cena z [pl.detailing.crm.subscription.pricing.PricingService.nextPeriodPrice]). Między
     * zamówieniem a płatnością właściciel mógł zmienić plany na kolejny okres; wygrywa to, za
     * co zapłacił (audyt, S1):
     *  - plan: zapłacił za bieżący — zaplanowany downgrade jest odwoływany; za niższy —
     *    downgrade planuje się na początek opłaconego okresu;
     *  - moduły: opłacony moduł z zaplanowanym wyłączeniem zostaje; aktywny moduł, którego nie
     *    ma w zamówieniu, wyłącza się z początkiem opłaconego okresu (przegląd planu naprawczego:
     *    dawniej zamówienie bez modułu, opłacone po dokupieniu modułu, dawało moduł na cały
     *    kolejny okres, a moduł opłacony w odnowieniu znikał, jeśli w międzyczasie go wyłączono).
     *
     * Gdy tego, za co zapłacono, nie da się już dostarczyć — kolejny okres jest opłacony
     * w innym planie (zamrożony downgrade) albo moduły musiałyby przetrwać zaplanowaną zmianę
     * planu — zamówienie idzie do zwrotu, a daty się nie zmieniają.
     */
    private fun applyRenewal(order: PaymentOrderEntity, studio: StudioEntity): Effect {
        if (studio.subscriptionStatus == SubscriptionStatus.NO_PLAN) {
            return Effect.NotApplicable("Studio nie ma pakietu do przedłużenia")
        }
        val studioId = StudioId(studio.id)
        val now = accessPolicy.now()
        val periodStart = accessPolicy.paidPeriodStart(studio.billing(), order.paidAt!!)
        val current = entitlementService.readCurrent(studioId)
        val paidPlanKey = order.planKey ?: current.planKey

        val alignment = when (val decision = planNextPeriod(studio, current, paidPlanKey, order.addOnKeys, periodStart, now)) {
            is NextPeriod.Refuse -> return Effect.NotApplicable(decision.reason)
            is NextPeriod.Align -> decision
        }

        startPaidPeriod(studio, periodStart)
        if (alignment.cancelPending) pendingPlanChangeRepository.cancelPendingForStudio(studio.id)
        alignment.changePlanNow?.let { entitlementService.changePlan(studioId, it) }
        alignment.schedulePlan?.let { target ->
            pendingPlanChangeRepository.save(
                PendingPlanChangeEntity(
                    studioId = studio.id, fromPlanKey = current.planKey, toPlanKey = target,
                    effectiveAt = periodStart, requestedAt = now
                )
            )
        }
        if (alignment.alignAddOns) alignAddOns(studioId, order.addOnKeys, periodStart, now)
        logger.info(
            "Studio={} renewal paid for plan {} add-ons {} from {} (alignment {})",
            studioId, paidPlanKey, order.addOnKeys, periodStart, alignment
        )
        return Effect.Applied(SubscriptionEventType.SUBSCRIPTION_RENEWAL)
    }

    private sealed interface NextPeriod {
        data class Refuse(val reason: String) : NextPeriod
        data class Align(
            val cancelPending: Boolean = false,
            val changePlanNow: PlanKey? = null,
            val schedulePlan: PlanKey? = null,
            /** Moduły kolejnego okresu = moduły z zamówienia (plan w tym okresie się nie zmienia albo zmienia się teraz). */
            val alignAddOns: Boolean = true
        ) : NextPeriod
    }

    /** Decyzja bez skutków — o zwrocie trzeba wiedzieć, zanim przesunie się daty. */
    private fun planNextPeriod(
        studio: StudioEntity,
        current: StudioEntitlements,
        paidPlanKey: PlanKey,
        paidAddOns: List<AddOnKey>,
        periodStart: Instant,
        now: Instant
    ): NextPeriod {
        val pending = pendingPlanChangeRepository.findByStudioIdAndStatus(studio.id, PendingPlanChangeStatus.PENDING)

        // Zmiana, za której okres już zapłacono (zamrożona), wejdzie przed kupowanym okresem
        // i zostaje — kupowany okres ma jej plan, bez modułów (zmiana planu je czyści).
        if (pending != null && studio.subscriptionEndsAt?.isAfter(pending.effectiveAt) == true) {
            if (paidPlanKey != pending.toPlanKey) {
                return NextPeriod.Refuse(
                    "Kolejny okres jest już opłacony w planie ${pending.toPlanKey.displayName} — odnowienie w planie ${paidPlanKey.displayName} nie ma gdzie wejść"
                )
            }
            if (paidAddOns.isNotEmpty()) {
                return NextPeriod.Refuse("Moduły z odnowienia nie przetrwają zaplanowanej zmiany planu na ${pending.toPlanKey.displayName}")
            }
            return NextPeriod.Align(alignAddOns = false)
        }

        if (paidPlanKey == current.planKey) return NextPeriod.Align(cancelPending = pending != null)
        if (pending != null && pending.toPlanKey == paidPlanKey && pending.effectiveAt == periodStart) {
            // Odnowienie wycenione po zaplanowanym downgradzie — zmiana już jest na swoim miejscu.
            return if (paidAddOns.isEmpty()) NextPeriod.Align(alignAddOns = false)
            else NextPeriod.Refuse("Moduły z odnowienia nie przetrwają zmiany planu na ${paidPlanKey.displayName} na początku okresu")
        }

        if (!periodStart.isAfter(now)) {
            return NextPeriod.Align(cancelPending = pending != null, changePlanNow = paidPlanKey)
        }
        if (paidAddOns.isNotEmpty()) {
            return NextPeriod.Refuse("Moduły z odnowienia nie przetrwają zmiany planu na ${paidPlanKey.displayName} na początku okresu")
        }
        return NextPeriod.Align(cancelPending = pending != null, schedulePlan = paidPlanKey, alignAddOns = false)
    }

    /**
     * Moduły kupowanego okresu = moduły z zamówienia. Opłacony moduł traci zaplanowane
     * wyłączenie (albo jest włączany, jeśli zdążył zniknąć), nieopłacony — wyłącza się
     * z początkiem okresu. Wcześniejsza data wyłączenia nigdy nie jest przesuwana.
     */
    private fun alignAddOns(studioId: StudioId, paidAddOns: List<AddOnKey>, periodStart: Instant, now: Instant) {
        val current = entitlementService.readCurrent(studioId)
        if (current.planKey == PlanKey.FULL) return   // FULL zawiera wszystkie moduły

        paidAddOns.forEach { key ->
            if (key in current.activeAddOnKeys) entitlementService.resumeAddOn(studioId, key)
            else entitlementService.activateAddOn(studioId, key)
        }
        (current.activeAddOnKeys - paidAddOns.toSet()).forEach { key ->
            val cancelAt = current.addOnCancellations[key]
            if (cancelAt == null || cancelAt.isAfter(periodStart)) {
                entitlementService.cancelAddOn(studioId, key, periodStart.takeIf { it.isAfter(now) })
            }
        }
    }

    private fun applyPlanUpgrade(order: PaymentOrderEntity, studio: StudioEntity): Effect {
        val planKey = requireNotNull(order.planKey) { "PLAN_UPGRADE order without planKey: ${order.id}" }
        val studioId = StudioId(studio.id)
        // Dopłata policzona do końca okresu P: po odnowieniu (okres sięga dalej) albo po jego
        // końcu upgrade dawałby wyższy plan na czas, za który dopłaty nie było.
        when (pricedPeriodState(order, studio)) {
            PricedPeriod.SAME, PricedPeriod.NOT_PRICED -> Unit
            PricedPeriod.EXTENDED -> return Effect.NotApplicable(
                "Okres, do którego policzono dopłatę (${BillingDates.format(order.pricedUntil!!)}), został w międzyczasie przedłużony — kup zmianę pakietu ponownie"
            )
            PricedPeriod.ENDED -> return Effect.NotApplicable(
                "Okres, do którego policzono dopłatę (${BillingDates.format(order.pricedUntil!!)}), skończył się przed płatnością"
            )
        }
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
        // Moduł działa do końca okresu, za który zapłacono — także gdy odnowienie (bez modułu)
        // zostało opłacone wcześniej i okres sięga już dalej.
        val capAt = when (pricedPeriodState(order, studio)) {
            PricedPeriod.SAME, PricedPeriod.NOT_PRICED -> null
            PricedPeriod.EXTENDED -> order.pricedUntil
            PricedPeriod.ENDED -> return Effect.NotApplicable(
                "Okres, do którego policzono dopłatę za moduł (${BillingDates.format(order.pricedUntil!!)}), skończył się przed płatnością"
            )
        }
        order.addOnKeys.forEach { key ->
            entitlementService.activateAddOn(studioId, key)
            capAt?.let { entitlementService.cancelAddOn(studioId, key, it) }
        }
        return Effect.Applied(SubscriptionEventType.ADD_ON_ACTIVATION)
    }

    private fun startPaidPeriod(studio: StudioEntity, periodStart: Instant) {
        studio.subscriptionStatus = SubscriptionStatus.ACTIVE
        studio.subscriptionEndsAt = periodStart.plus(BILLING_PERIOD_DAYS, ChronoUnit.DAYS)
        // Trwający trial zostaje zapisany: jego reszta jest darmowa i proracja nie może jej liczyć
        // jako opłaconego czasu (SubscriptionLifecycle.billableFrom).
        studio.trialEndsAt = studio.trialEndsAt?.takeIf { it.isAfter(accessPolicy.now()) }
        studio.graceEndsAt = null
    }

    private enum class PricedPeriod { NOT_PRICED, SAME, EXTENDED, ENDED }

    /** Czy okres, do którego policzono dopłatę proporcjonalną, w chwili płatności wciąż trwał i się nie wydłużył. */
    private fun pricedPeriodState(order: PaymentOrderEntity, studio: StudioEntity): PricedPeriod {
        val pricedUntil = order.pricedUntil ?: return PricedPeriod.NOT_PRICED
        val billing = studio.billing()
        if (!accessPolicy.hasRunningPaidPeriod(billing, order.paidAt!!)) return PricedPeriod.ENDED
        return if (billing.subscriptionEndsAt!!.isAfter(pricedUntil)) PricedPeriod.EXTENDED else PricedPeriod.SAME
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
