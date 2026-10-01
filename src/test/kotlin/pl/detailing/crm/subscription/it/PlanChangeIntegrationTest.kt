package pl.detailing.crm.subscription.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.detailing.crm.payments.checkout.CheckoutRequest
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.subscription.entitlement.AddOnCancellationResult
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.sql.Timestamp
import java.time.Duration

/**
 * Zmiany planu i modułów w trakcie okresu. Reguła, którą sprawdzają wszystkie testy:
 * to, za co zapłacono, działa do końca opłaconego okresu, a to, co obowiązuje w kolejnym
 * okresie, jest dokładnie tym, co wycenia odnowienie.
 */
class PlanChangeIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "plan_change")
    }

    private fun renewalPrice(studioId: java.util.UUID) = pricingService.nextPeriodPrice(StudioId(studioId)).amountCents

    // ── Downgrade ────────────────────────────────────────────────────────────

    @Test
    fun `downgrade w trakcie okresu czeka do jego konca, a odnowienie juz go wycenia`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(10))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)

        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)

        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(listOf("PENDING"), pendingStatuses(studioId))
        assertEquals(periodEnd, jdbc.queryForObject("SELECT effective_at FROM pending_plan_changes WHERE studio_id = ?", Timestamp::class.java, studioId)!!.toInstant())
        assertEquals(BASIC_PRICE, renewalPrice(studioId))
    }

    @Test
    fun `downgrade w trialu i po terminie dziala od razu`() {
        val trial = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.FULL, trialEndsAt = clock.instant().plus(Duration.ofDays(20)))
        val expired = studioWithPlan(SubscriptionStatus.EXPIRED, PlanKey.FULL, endsAt = clock.instant().minus(Duration.ofDays(20)))

        planManagementService.schedulePlanDowngrade(StudioId(trial), PlanKey.BASIC)
        planManagementService.schedulePlanDowngrade(StudioId(expired), PlanKey.BASIC)

        assertEquals(PlanKey.BASIC, planOf(trial))
        assertEquals(PlanKey.BASIC, planOf(expired))
        assertEquals(emptyList<String>(), pendingStatuses(trial))
    }

    @Test
    fun `ponowne zaplanowanie downgrade'u zastepuje poprzednie - jeden PENDING na studio`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = clock.instant().plus(Duration.ofDays(10)))

        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        clock.advance(Duration.ofMinutes(1))
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)

        assertEquals(listOf("CANCELLED", "PENDING"), pendingStatuses(studioId))
    }

    @Test
    fun `odwolanie downgrade'u przed odnowieniem przywraca cene FULL`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = clock.instant().plus(Duration.ofDays(10)))
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        assertEquals(BASIC_PRICE, renewalPrice(studioId))

        assertTrue(planManagementService.cancelPendingDowngrade(StudioId(studioId)))
        assertEquals(listOf("CANCELLED"), pendingStatuses(studioId))
        assertEquals(FULL_PRICE, renewalPrice(studioId))
    }

    @Test
    fun `upgrade w trakcie okresu - doplata proporcjonalna z zaliczeniem oplaconego modulu`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 15, addOns = listOf(AddOnKey.FINANCE_MODULE))

        val upgrade = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))

        // (29900 − 9900 − 4900) × 15/30 = 7550: moduł finansów jest już opłacony do końca okresu.
        assertEquals(7_550L, upgrade.amountCents)
        payAndNotify(orderRepository.findById(upgrade.orderId).orElseThrow())
        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
        assertEquals(FULL_PRICE, renewalPrice(studioId))
    }

    @Test
    fun `upgrade w trialu jest darmowy i natychmiastowy`() {
        val studioId = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = clock.instant().plus(Duration.ofDays(20)))

        val upgrade = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))

        assertEquals(0L, upgrade.amountCents)
        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(SubscriptionStatus.TRIALING, statusOf(studioId))
    }

    @Test
    fun `odnowienie zamowione po FULL i oplacone po zaplanowaniu downgrade'u - wygrywa to, za co zaplacono`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(5))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        val renewal = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL))
        assertEquals(FULL_PRICE, renewal.amountCents)

        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        payAndNotify(orderRepository.findById(renewal.orderId).orElseThrow())

        assertEquals(listOf("CANCELLED"), pendingStatuses(studioId), "zapłacono za FULL — downgrade odwołany")
        clock.advance(Duration.ofDays(6))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(periodEnd.plus(Duration.ofDays(30)), endsAtOf(studioId))
    }

    @Test
    fun `odnowienie wycenione po BASIC i oplacone po odwolaniu downgrade'u - kolejny okres jest BASIC`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(5))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        val renewal = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL))
        assertEquals(BASIC_PRICE, renewal.amountCents)
        assertTrue(planManagementService.cancelPendingDowngrade(StudioId(studioId)))

        payAndNotify(orderRepository.findById(renewal.orderId).orElseThrow())

        // Zapłacono za BASIC: FULL do końca bieżącego okresu, potem BASIC — bez dopłaty nie ma FULL.
        assertEquals(listOf("CANCELLED", "PENDING"), pendingStatuses(studioId))
        clock.advance(Duration.ofDays(5).plusMinutes(1))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(PlanKey.BASIC, planOf(studioId))
    }

    // ── Moduły ───────────────────────────────────────────────────────────────

    @Test
    fun `wylaczenie modulu w trakcie okresu - dziala do konca, nie wchodzi do odnowienia, znika z koncem okresu`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 10, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val periodEnd = endsAtOf(studioId)!!
        assertEquals(BASIC_PRICE + FINANCE_PRICE, renewalPrice(studioId))

        assertEquals(AddOnCancellationResult.SCHEDULED, planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE))

        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
        assertEquals(periodEnd, jdbc.queryForObject("SELECT cancel_at FROM studio_subscription_add_ons", Timestamp::class.java)!!.toInstant())
        assertEquals(BASIC_PRICE, renewalPrice(studioId))
        assertEquals(periodEnd, entitlementService.getEntitlements(StudioId(studioId)).addOnCancellations[AddOnKey.FINANCE_MODULE])

        clock.advance(Duration.ofDays(10).plusMinutes(1))
        downgradeScheduler.applyDueDowngrades()

        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
        assertEquals(2L, ledgerCount(studioId, "ADD_ON_DEACTIVATION"), "zaplanowanie i wykonanie")
    }

    @Test
    fun `cofniecie wylaczenia - modul zostaje i wraca do ceny odnowienia`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 10, addOns = listOf(AddOnKey.FINANCE_MODULE))
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        planManagementService.resumeAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        assertNull(jdbc.queryForObject("SELECT cancel_at FROM studio_subscription_add_ons", Timestamp::class.java))
        assertEquals(BASIC_PRICE + FINANCE_PRICE, renewalPrice(studioId))
        clock.advance(Duration.ofDays(11))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    @Test
    fun `wylaczony modul nie jest sprzedawany drugi raz - komunikat kaze go przywrocic`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 10, addOns = listOf(AddOnKey.FINANCE_MODULE))
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        val error = assertThrows<ValidationException> {
            checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        }
        assertTrue(error.message!!.contains("11.10.2026"), error.message)
    }

    @Test
    fun `wylaczenie modulu w trialu dziala od razu`() {
        val studioId = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = clock.instant().plus(Duration.ofDays(20)),
            addOns = listOf(AddOnKey.FINANCE_MODULE))

        assertEquals(AddOnCancellationResult.REMOVED, planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE))
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
    }

    @Test
    fun `wylaczony z koncem okresu modul nadal zalicza sie przy upgradzie`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 15, addOns = listOf(AddOnKey.FINANCE_MODULE))
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        val upgrade = checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))

        // Moduł wyłączany z końcem okresu nadal jest opłacony do tego końca — kredyt ten sam co bez wyłączenia.
        assertEquals(7_550L, upgrade.amountCents)
    }
}
