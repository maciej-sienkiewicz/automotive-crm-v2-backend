package pl.detailing.crm.subscription.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.detailing.crm.payments.checkout.CheckoutRequest
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionConflictException
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.management.PlanDowngradeScheduler
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Dziewięć defektów z `docs/SUBSCRIPTION_AUDIT_2026-10.md`, odtworzonych na Postgresie
 * przed naprawą (dawny `SubscriptionKnownDefectsTest`, wtedy czerwony z opisanego powodu),
 * teraz jako testy POPRAWNEGO zachowania. Identyfikator defektu jest w nazwie testu.
 *
 * Scenariusze są te same co w odtworzeniu — zmieniła się tylko droga wejścia tam, gdzie
 * zniknęło API, przez które defekt przechodził (np. `completeOrder` → notyfikacja P24
 * przez inbox). Bramki (`CountDownLatch`) wstrzymują jedną transakcję dokładnie w miejscu,
 * w którym wyścig był groźny, więc wynik nie zależy od szczęścia w przeplocie wątków.
 */
class SubscriptionAuditRegressionIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "audit_regression")
    }

    // ── J1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `J1 awaria jednego studia nie cofa downgrade'ow pozostalych`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(3))
        val healthy = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        val poisoned = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        listOf(healthy, poisoned).forEach { planManagementService.schedulePlanDowngrade(StudioId(it), PlanKey.BASIC) }
        assertEquals(PlanKey.FULL, planOf(healthy), "downgrade czeka do końca okresu")

        clock.advance(Duration.ofDays(3).plusMinutes(1))
        poisonWrites("studio_subscription_plans", "studio_id", poisoned)
        val failuresBefore = counter("subscription.scheduled.changes.failures")

        downgradeScheduler.applyDueDowngrades()

        assertEquals(PlanKey.BASIC, planOf(healthy))
        assertEquals(listOf("APPLIED"), pendingStatuses(healthy))
        assertEquals(PlanKey.FULL, planOf(poisoned))
        assertEquals(listOf("PENDING"), pendingStatuses(poisoned), "nieudany wiersz czeka na ponowienie")
        assertEquals(failuresBefore + 1, counter("subscription.scheduled.changes.failures"))

        // Awaria minęła — kolejny przebieg kończy zaległą zmianę.
        dropPoisonTriggers()
        downgradeScheduler.applyDueDowngrades()
        assertEquals(PlanKey.BASIC, planOf(poisoned))
        assertEquals(listOf("APPLIED"), pendingStatuses(poisoned))
    }

    // ── J2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `J2 awaria jednego studia nie zatrzymuje wygaszania pozostalych`() {
        val now = clock.instant()
        val trials = (1..5).map { studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = now.minus(Duration.ofDays(1))) }
        val poisoned = trials[2]
        val lapsedPastGrace = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = now.minus(Duration.ofDays(8)))
        val lapsedInGrace = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = now.minus(Duration.ofDays(2)))
        val stillPaid = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = now.plus(Duration.ofDays(2)))
        poisonWrites("studios", "id", poisoned, condition = "NEW.subscription_status = 'EXPIRED'")
        val failuresBefore = counter("subscription.lifecycle.failures")

        lifecycleJob.advanceDueSubscriptions()

        (trials - poisoned).forEach { assertEquals(SubscriptionStatus.EXPIRED, statusOf(it), "studio $it") }
        assertEquals(SubscriptionStatus.TRIALING, statusOf(poisoned), "awaria jednego studia cofa tylko jego przejście")
        assertEquals(SubscriptionStatus.EXPIRED, statusOf(lapsedPastGrace))
        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(lapsedInGrace), "po końcu okresu studio wchodzi w karencję")
        assertEquals(endsAtOf(lapsedInGrace)!!.plus(Duration.ofDays(7)), graceEndsAtOf(lapsedInGrace))
        assertEquals(SubscriptionStatus.ACTIVE, statusOf(stillPaid))
        assertEquals(failuresBefore + 1, counter("subscription.lifecycle.failures"))
    }

    // ── P1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P1 rownolegly duplikat notyfikacji przedluza abonament dokladnie raz`() {
        val endsAt = clock.instant().plus(Duration.ofDays(10))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = endsAt)
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC)
        val p24OrderId = gateway.pay(order.sessionId, order.amountCents)
        val notification = signedNotification(order.sessionId, order.amountCents, p24OrderId)

        // Pierwsza obsługa stoi w `verify` (HTTP do P24), gdy przychodzi ponowienie tej samej
        // notyfikacji i przechodzi całą ścieżkę. Dawniej obie realizowały zamówienie: +60 dni.
        val firstInVerify = CountDownLatch(1)
        val duplicateDone = CountDownLatch(1)
        val gateArmed = AtomicBoolean(true)
        gateway.beforeVerify = {
            if (gateArmed.compareAndSet(true, false)) {
                firstInVerify.countDown()
                duplicateDone.await(10, TimeUnit.SECONDS)
            }
        }
        var firstOutcome: Any? = null
        val first = thread { firstOutcome = notificationProcessor.process(notificationProcessor.record(notification)) }
        assertTrue(firstInVerify.await(10, TimeUnit.SECONDS))
        val duplicateOutcome = notificationProcessor.process(notificationProcessor.record(notification))
        duplicateDone.countDown()
        first.join()

        assertEquals(endsAt.plus(Duration.ofDays(30)), endsAtOf(studioId), "przedłużenie o jeden okres, nie dwa ($firstOutcome / $duplicateOutcome)")
        assertEquals(1L, ledgerCount(studioId, "SUBSCRIPTION_RENEWAL"))
        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM payment_notifications", Long::class.java))
    }

    @Test
    fun `P1 burza duplikatow bez bramek - jeden efekt`() {
        val endsAt = clock.instant().plus(Duration.ofDays(10))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = endsAt)
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC)
        val notification = signedNotification(order.sessionId, order.amountCents, gateway.pay(order.sessionId, order.amountCents))

        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..6).map {
            thread {
                start.await()
                runCatching { notificationProcessor.process(notificationProcessor.record(notification)) }.onFailure { errors += it }
            }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertTrue(errors.isEmpty(), "błędy: ${errors.map { "${it.javaClass.simpleName}: ${it.message}" }}")
        assertEquals(endsAt.plus(Duration.ofDays(30)), endsAtOf(studioId))
        assertEquals(1L, ledgerCount(studioId, "SUBSCRIPTION_RENEWAL"))
        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
    }

    // ── S2 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S2 potwierdzone anulowanie downgrade'u jest wiazace`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = clock.instant().plus(Duration.ofDays(1)))
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        clock.advance(Duration.ofDays(1).plusMinutes(1))

        // Przebieg schedulera wczytał należne zmiany; zanim je zastosuje, właściciel klika „Anuluj".
        val loaded = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val gatedPending = object : PendingPlanChangeRepository by pendingRepository {
            override fun findDueIds(now: Instant, pageable: Pageable): List<UUID> =
                pendingRepository.findDueIds(now, pageable).also { loaded.countDown(); cancelled.await(10, TimeUnit.SECONDS) }
        }
        val scheduler = PlanDowngradeScheduler(gatedPending, studioAddOnRepository, scheduledChangeApplier, accessPolicy, meterRegistry)
        val run = thread { scheduler.applyDueDowngrades() }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        val userWasToldCancelled = planManagementService.cancelPendingDowngrade(StudioId(studioId))
        cancelled.countDown()
        run.join()

        assertTrue(userWasToldCancelled)
        assertEquals(PlanKey.FULL, planOf(studioId), "„anulowano” musi znaczyć, że plan zostaje")
        assertEquals(listOf("CANCELLED"), pendingStatuses(studioId))
    }

    @Test
    fun `S2 anulowanie po zastosowaniu downgrade'u mowi prawde`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = clock.instant().plus(Duration.ofDays(1)))
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        clock.advance(Duration.ofDays(1).plusMinutes(1))

        downgradeScheduler.applyDueDowngrades()

        assertFalse(planManagementService.cancelPendingDowngrade(StudioId(studioId)), "nie ma już czego anulować")
        assertEquals(PlanKey.BASIC, planOf(studioId))
        assertEquals(listOf("APPLIED"), pendingStatuses(studioId))
    }

    // ── S1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S1 odnowienie po zaplanowanym downgradzie kosztuje tyle co plan nastepnego okresu`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(5))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)

        val renewal = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL))
        assertEquals(BASIC_PRICE, renewal.amountCents, "odnowienie liczy cenę planu, który będzie obowiązywał")
        payAndNotify(orderRepository.findById(renewal.orderId).orElseThrow())

        assertEquals(PlanKey.FULL, planOf(studioId), "do końca bieżącego okresu zostaje opłacony FULL")
        assertEquals(periodEnd.plus(Duration.ofDays(30)), endsAtOf(studioId))

        clock.advance(Duration.ofDays(5).plusMinutes(1))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(PlanKey.BASIC, planOf(studioId), "w opłaconym okresie studio ma plan, za który zapłaciło")

        // Opłaconego w cenie BASIC okresu nie da się już zamienić na FULL odwołaniem downgrade'u.
        assertEquals(listOf("APPLIED"), pendingStatuses(studioId))
    }

    @Test
    fun `S1 po oplaceniu odnowienia w nizszym planie downgrade nie daje sie odwolac`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(5))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        val renewal = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL))
        payAndNotify(orderRepository.findById(renewal.orderId).orElseThrow())

        val conflict = assertThrows<SubscriptionConflictException> {
            planManagementService.cancelPendingDowngrade(StudioId(studioId))
        }
        assertEquals("DOWNGRADE_ALREADY_PAID", conflict.code)
        assertEquals(listOf("PENDING"), pendingStatuses(studioId))
    }

    // ── S4 ───────────────────────────────────────────────────────────────────

    @Test
    fun `S4 upgrade studia z wygasla subskrypcja nie jest darmowy`() {
        val studioId = studioWithPlan(SubscriptionStatus.EXPIRED, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(30)))

        assertThrows<ValidationException> {
            checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))
        }
        assertFalse(planManagementService.previewPlanChange(StudioId(studioId), PlanKey.FULL).allowed)
        assertEquals(PlanKey.BASIC, planOf(studioId))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM payment_orders", Long::class.java))
    }

    @Test
    fun `S4 w karencji zmiana planu takze czeka na odnowienie`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(2)))
        lifecycleJob.advanceDueSubscriptions()
        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(studioId))

        assertThrows<ValidationException> {
            checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        }
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
    }

    // ── P7 ───────────────────────────────────────────────────────────────────

    @Test
    fun `P7 drugie zamowienie na ten sam modul wskazuje pierwsze`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 20)
        val request = CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE))

        // Dwie karty przeglądarki / ponowne kliknięcie „Kup moduł".
        val first = checkoutService.checkout(StudioId(studioId), BUYER, request)
        val second = checkoutService.checkout(StudioId(studioId), BUYER, request)

        assertEquals(first.orderId, second.orderId)
        assertEquals(first.paymentUrl, second.paymentUrl)
        assertEquals(1, gateway.count(HttpMethod.POST, "/api/v1/transaction/register"))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM payment_orders WHERE status = 'PENDING'", Long::class.java))
    }

    @Test
    fun `P7 drugi oplacony zakup tego samego modulu konczy sie zwrotem, nie cisza`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 20)
        val first = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_267, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE))
        payAndNotify(first)
        // Drugie zamówienie zostało opłacone w drugiej karcie, zanim pierwsze się zrealizowało.
        val second = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_267, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE),
            status = PaymentOrderStatus.EXPIRED)
        payAndNotify(second)

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(first.id))
        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(second.id))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
        assertEquals(1.0, counter("payments.refund.required"))
    }

    // ── D1 ───────────────────────────────────────────────────────────────────

    @Test
    fun `D1 dwie rownolegle aktywacje tego samego modulu nie koncza sie DataIntegrityViolation`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 20)

        // B wczytał plan (jak realizacja na starcie), A w tym czasie aktywuje moduł i zatwierdza.
        val bLoaded = CountDownLatch(1)
        val aCommitted = CountDownLatch(1)
        var bError: Throwable? = null
        var bResult: Boolean? = null
        val b = thread {
            runCatching {
                tx.execute {
                    subscriptionPlanRepository.findByStudioIdWithAddOns(studioId)!!.activeAddOns.size
                    bLoaded.countDown(); aCommitted.await(10, TimeUnit.SECONDS)
                    entitlementService.activateAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
                }
            }.onSuccess { bResult = it }.onFailure { bError = it }
        }
        assertTrue(bLoaded.await(10, TimeUnit.SECONDS))
        assertTrue(entitlementService.activateAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE))
        aCommitted.countDown()
        b.join()

        assertNull(bError, "druga aktywacja: ${bError?.message}")
        assertEquals(false, bResult, "druga aktywacja widzi moduł i to zgłasza")
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_add_ons", Long::class.java))
    }

    @Test
    fun `D1 rownolegle pierwsze przypisanie planu jest idempotentne`() {
        val studioId = studio(SubscriptionStatus.NO_PLAN)
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..6).map {
            thread { start.await(); runCatching { entitlementService.ensurePlanAssigned(StudioId(studioId)) }.onFailure { errors += it } }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertTrue(errors.isEmpty(), "błędy: ${errors.map { it.javaClass.simpleName }}")
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM studio_subscription_plans WHERE studio_id = ?", Long::class.java, studioId))
        assertEquals(PlanKey.BASIC, planOf(studioId))
    }

    // ── Strażnik refaktoru ───────────────────────────────────────────────────

    @Test
    fun `GUARD zakup po wygasnieciu i upgrade - zmiana planu i modulow w jednej transakcji`() {
        val studioId = studioWithPlan(SubscriptionStatus.EXPIRED, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(1)),
            addOns = listOf(AddOnKey.FINANCE_MODULE))

        // Zakup po wygaśnięciu: zmiana planu czyści moduły (orphanRemoval), a zamówienie wstawia
        // ten sam moduł — w jednym flushu. DELETE sieroty musi wyprzedzić INSERT (uq_studio_add_ons).
        val rebuy = checkoutService.checkout(StudioId(studioId), BUYER,
            CheckoutRequest(type = PaymentOrderType.INITIAL_PURCHASE, planKey = PlanKey.BASIC, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        assertEquals(BASIC_PRICE + FINANCE_PRICE, rebuy.amountCents)
        payAndNotify(orderRepository.findById(rebuy.orderId).orElseThrow())
        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
        assertEquals(clock.instant().plus(Duration.ofDays(30)), endsAtOf(studioId))

        val upgrade = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))
        payAndNotify(orderRepository.findById(upgrade.orderId).orElseThrow())
        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId), "FULL zawiera moduły — osobne wiersze znikają")
        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(upgrade.orderId))
    }
}
