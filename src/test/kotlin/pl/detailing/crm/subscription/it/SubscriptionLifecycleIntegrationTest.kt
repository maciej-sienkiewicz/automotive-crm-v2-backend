package pl.detailing.crm.subscription.it

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.detailing.crm.payments.checkout.CheckoutRequest
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionInactiveException
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityLock
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Cykl życia subskrypcji w czasie: trial → zakup → koniec okresu → karencja → blokada,
 * oraz odnowienie w każdym z tych momentów. Czas płynie przez [MutableClock], a przejścia
 * robi ten sam job, który biegnie na produkcji co 10 minut.
 *
 * „Zablokowane" znaczy tu dokładnie: dane i kupiony plan zostają (studio nie traci nic
 * poza dostępem), a każdy punkt egzekwowania — `requireCapability`, `validateAccess` —
 * odmawia z sygnałem „subskrypcja nieaktywna", nie „brak modułu". Po zapłacie dostęp wraca
 * w tej samej chwili, bez czekania na job.
 */
class SubscriptionLifecycleIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "lifecycle")
    }

    // ── Trial ────────────────────────────────────────────────────────────────

    @Test
    fun `trial raz na studio - start daje plan, drugi start jest odrzucany`() {
        val studioId = studio(SubscriptionStatus.NO_PLAN)

        val info = subscriptionService.startTrial(StudioId(studioId))

        assertEquals(SubscriptionStatus.TRIALING, info.status)
        assertEquals(PlanKey.BASIC, planOf(studioId))
        assertTrue(capabilityService.isSubscriptionUsable(StudioId(studioId)))
        assertThrows<ValidationException> { subscriptionService.startTrial(StudioId(studioId)) }
    }

    @Test
    fun `po wygasnieciu trialu i oplaconego okresu nie da sie wystartowac trialu`() {
        val expired = studioWithPlan(SubscriptionStatus.EXPIRED, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(3)))
        jdbc.update("UPDATE studios SET trial_used = true WHERE id = ?", expired)
        assertThrows<ValidationException> { subscriptionService.startTrial(StudioId(expired)) }

        val active = activeStudio(PlanKey.BASIC)
        assertThrows<ValidationException> { subscriptionService.startTrial(StudioId(active)) }
    }

    @Test
    fun `koniec trialu blokuje dostep - z sygnalem subskrypcji, nie braku modulu`() {
        val studioId = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.FULL, trialEndsAt = clock.instant().plus(Duration.ofDays(1)))
        assertTrue(capabilityService.hasCapability(StudioId(studioId), CapabilityKey.COMM_SEND_TRANSACTIONAL))

        clock.advance(Duration.ofDays(1).plusSeconds(1))
        // Dostęp kończy się co do sekundy, także zanim job zdąży zmienić status w bazie.
        assertFalse(capabilityService.hasCapability(StudioId(studioId), CapabilityKey.COMM_SEND_TRANSACTIONAL))

        lifecycleJob.advanceDueSubscriptions()

        assertEquals(SubscriptionStatus.EXPIRED, statusOf(studioId))
        assertEquals(PlanKey.FULL, planOf(studioId), "kupiony plan zostaje — zablokowany jest tylko dostęp")
        val decision = capabilityService.resolveOne(StudioId(studioId), CapabilityKey.COMM_SEND_TRANSACTIONAL)
        assertEquals(CapabilityLock.SUBSCRIPTION, decision.lockedBy)
        assertTrue(decision.upsell.isEmpty(), "nie sprzedajemy modułu, który studio już ma")
        assertThrows<SubscriptionInactiveException> {
            capabilityService.requireCapability(StudioId(studioId), CapabilityKey.COMM_SEND_TRANSACTIONAL)
        }
        assertThrows<ForbiddenException> { runBlocking { subscriptionService.validateAccess(StudioId(studioId)) } }
    }

    @Test
    fun `zakup w trakcie trialu nie zjada reszty trialu - okres zaczyna sie z jego koncem`() {
        val trialEnd = clock.instant().plus(Duration.ofDays(12))
        val studioId = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = trialEnd)

        val purchase = checkoutService.checkout(StudioId(studioId), BUYER,
            CheckoutRequest(type = PaymentOrderType.INITIAL_PURCHASE, planKey = PlanKey.FULL))
        payAndNotify(orderRepository.findById(purchase.orderId).orElseThrow())

        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId))
        assertEquals(trialEnd.plus(Duration.ofDays(30)), endsAtOf(studioId))
        assertEquals(PlanKey.FULL, planOf(studioId))
    }

    // ── Karencja ─────────────────────────────────────────────────────────────

    @Test
    fun `koniec okresu - karencja z dostepem, odnowienie w karencji liczy okres od starego konca`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(2))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = periodEnd, addOns = listOf(AddOnKey.FINANCE_MODULE))

        clock.advance(Duration.ofDays(3))
        lifecycleJob.advanceDueSubscriptions()

        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(studioId))
        assertEquals(periodEnd.plus(Duration.ofDays(7)), graceEndsAtOf(studioId))
        assertTrue(capabilityService.isSubscriptionUsable(StudioId(studioId)), "karencja nie odcina pracy")
        runBlocking { subscriptionService.validateAccess(StudioId(studioId)) }
        val info = runBlocking { subscriptionService.getSubscriptionInfo(StudioId(studioId)) }
        assertTrue(info.inGrace)
        assertEquals(6L, info.daysRemaining, "dni do końca karencji, nie do końca okresu")

        val renewal = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL))
        assertEquals(BASIC_PRICE + FINANCE_PRICE, renewal.amountCents)
        payAndNotify(orderRepository.findById(renewal.orderId).orElseThrow())

        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId))
        assertEquals(periodEnd.plus(Duration.ofDays(30)), endsAtOf(studioId), "karencja nie jest darmowym przedłużeniem dla płacących")
        assertNull(graceEndsAtOf(studioId))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    @Test
    fun `brak oplaty do konca karencji blokuje konto, a zaplata przywraca dostep od razu`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(1))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = periodEnd, addOns = listOf(AddOnKey.FINANCE_MODULE))

        clock.advance(Duration.ofDays(2))
        lifecycleJob.advanceDueSubscriptions()
        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(studioId))
        assertTrue(capabilityService.hasCapability(StudioId(studioId), CapabilityKey.FINANCE_ACCESS))

        clock.advance(Duration.ofDays(7))
        lifecycleJob.advanceDueSubscriptions()

        assertEquals(SubscriptionStatus.EXPIRED, statusOf(studioId))
        assertNull(graceEndsAtOf(studioId))
        assertFalse(capabilityService.isSubscriptionUsable(StudioId(studioId)))
        val capabilities = capabilityService.resolve(StudioId(studioId))
        assertTrue(capabilities.decisions.values.all { !it.enabled && it.lockedBy == CapabilityLock.SUBSCRIPTION })
        assertThrows<SubscriptionInactiveException> { capabilityService.requireCapability(StudioId(studioId), CapabilityKey.FINANCE_ACCESS) }
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId), "opłacone moduły czekają na odnowienie")

        // Zmiana planu i moduły czekają na odnowienie — po terminie płaci się najpierw za okres.
        assertThrows<ValidationException> {
            checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))
        }
        assertThrows<ValidationException> {
            checkoutService.checkout(StudioId(studioId), BUYER,
                CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.STATISTICS_MODULE)))
        }

        val renewal = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL))
        assertEquals(BASIC_PRICE + FINANCE_PRICE, renewal.amountCents)
        payAndNotify(orderRepository.findById(renewal.orderId).orElseThrow())

        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId))
        // Zapłata dzień po karencji: wykorzystane 7 dni karencji wchodzi do opłaconego okresu.
        assertEquals(clock.instant().minus(Duration.ofDays(7)).plus(Duration.ofDays(30)), endsAtOf(studioId))
        assertTrue(capabilityService.hasCapability(StudioId(studioId), CapabilityKey.FINANCE_ACCESS), "dostęp wraca bez czekania na job")
    }

    @Test
    fun `PAST_DUE z minionym koncem karencji jest wygaszany przy pierwszym przebiegu`() {
        // Np. job nie biegł przez dłuższą przerwę (wdrożenie, awaria) — zaległość nadrabia jednym przejściem.
        val studioId = studioWithPlan(SubscriptionStatus.PAST_DUE, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(8)),
            graceEndsAt = clock.instant().minus(Duration.ofDays(1)))

        lifecycleJob.advanceDueSubscriptions()

        assertEquals(SubscriptionStatus.EXPIRED, statusOf(studioId))
    }

    @Test
    fun `PAST_DUE sprzed V172 bez daty konca karencji - karencja liczona od konca okresu`() {
        val inGrace = studioWithPlan(SubscriptionStatus.PAST_DUE, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(3)))
        val pastGrace = studioWithPlan(SubscriptionStatus.PAST_DUE, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(8)))

        // Studio w karencji nie jest „należne" — nie wraca w każdej porcji joba przez 7 dni.
        assertEquals(listOf(pastGrace), studioRepository.findIdsDueForLifecycleTransition(
            clock.instant(), clock.instant().minus(Duration.ofDays(7)), org.springframework.data.domain.PageRequest.of(0, 10)))
        lifecycleJob.advanceDueSubscriptions()

        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(inGrace))
        assertTrue(capabilityService.isSubscriptionUsable(StudioId(inGrace)))
        assertEquals(SubscriptionStatus.EXPIRED, statusOf(pastGrace))
    }

    // ── Job ──────────────────────────────────────────────────────────────────

    @Test
    fun `job jest idempotentny - drugi przebieg niczego nie zmienia`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofHours(1)))
        lifecycleJob.advanceDueSubscriptions()
        val grace = graceEndsAtOf(studioId)
        val transitions = counter("subscription.lifecycle.transitions")

        lifecycleJob.advanceDueSubscriptions()

        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(studioId))
        assertEquals(grace, graceEndsAtOf(studioId))
        assertEquals(transitions, counter("subscription.lifecycle.transitions"))
    }

    @Test
    fun `studio zablokowane przez trwajaca platnosc jest pomijane, a nie czeka - i trafia do kolejnego przebiegu`() {
        val busy = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = clock.instant().minus(Duration.ofMinutes(5)))
        val other = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = clock.instant().minus(Duration.ofMinutes(5)))

        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            tx.executeWithoutResult {
                studioRepository.lockById(busy)
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue(locked.await(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        lifecycleJob.advanceDueSubscriptions()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        holder.join()

        assertTrue(tookMs < 5_000, "job nie czeka na cudzą blokadę (SKIP LOCKED), trwał $tookMs ms")
        assertEquals(SubscriptionStatus.TRIALING, statusOf(busy))
        assertEquals(SubscriptionStatus.EXPIRED, statusOf(other))

        lifecycleJob.advanceDueSubscriptions()
        assertEquals(SubscriptionStatus.EXPIRED, statusOf(busy))
    }

    @Test
    fun `platnosc w trakcie przejscia do EXPIRED - wynik zalezy od kolejnosci, ale zawsze jest spojny`() {
        // Odnowienie i job ścigają się o to samo studio; blokada studia ustawia je w kolejce.
        val periodEnd = clock.instant().minus(Duration.ofDays(8))
        val studioId = studioWithPlan(SubscriptionStatus.PAST_DUE, PlanKey.BASIC, endsAt = periodEnd,
            graceEndsAt = periodEnd.plus(Duration.ofDays(7)))
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC)

        val start = CountDownLatch(1)
        val job = thread { start.await(); lifecycleJob.advanceDueSubscriptions() }
        val payment = thread { start.await(); payAndNotify(order) }
        start.countDown()
        job.join(); payment.join()
        lifecycleJob.advanceDueSubscriptions()   // gdyby job przegrał wyścig o blokadę

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId), "opłacone studio nie zostaje wygaszone")
        assertEquals(clock.instant().minus(Duration.ofDays(7)).plus(Duration.ofDays(30)), endsAtOf(studioId),
            "ta sama data bez względu na to, kto wygrał wyścig")
        assertNull(graceEndsAtOf(studioId))
    }
}
