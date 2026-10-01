package pl.detailing.crm.subscription.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import java.sql.Timestamp
import java.time.Duration
import java.util.UUID

/**
 * Pieniądze przy „spiętrzonych" okresach: kolejny okres opłacony, zanim skończył się bieżący,
 * i zmiany (downgrade, moduły, stare zamówienia) wprowadzane PO tej płatności.
 *
 * Każdy scenariusz to odtworzenie z przeglądu planu naprawczego (R1–R10): przed poprawką
 * dawał plan albo moduł bez zapłaty (albo brał pieniądze bez efektu). Reguła, której pilnują:
 * **to, co zapłacono za kolejny okres, jest zamrożone** — zmiana po zapłacie nie może go
 * rozszerzyć, a stare zamówienie jest przy realizacji porównywane z tym, za co zapłacono.
 */
class StackedPeriodMoneyIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "stacked_money")
    }

    private fun checkout(studioId: UUID, request: CheckoutRequest) = checkoutService.checkout(StudioId(studioId), BUYER, request)
    private fun renewal(studioId: UUID) = checkout(studioId, CheckoutRequest(type = PaymentOrderType.RENEWAL))
    private fun pay(orderId: UUID) = payAndNotify(orderRepository.findById(orderId).orElseThrow())
    private fun cancelAtOf(studioId: UUID): java.time.Instant? = jdbc.queryForList(
        """SELECT sa.cancel_at FROM studio_subscription_add_ons sa
           JOIN studio_subscription_plans s ON s.id = sa.studio_subscription_plan_id WHERE s.studio_id = ?""",
        Timestamp::class.java, studioId
    ).firstOrNull()?.toInstant()

    // ── Downgrade ────────────────────────────────────────────────────────────

    @Test
    fun `R1 ponowne zaplanowanie downgrade'u po oplaceniu okresu w BASIC nie przesuwa go ani nie odblokowuje`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(5))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        pay(renewal(studioId).orderId)

        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)   // no-op

        assertEquals(listOf("PENDING"), pendingStatuses(studioId))
        assertEquals(periodEnd, jdbc.queryForObject("SELECT effective_at FROM pending_plan_changes WHERE studio_id = ?", Timestamp::class.java, studioId)!!.toInstant())
        assertThrows<SubscriptionConflictException> { planManagementService.cancelPendingDowngrade(StudioId(studioId)) }
        clock.advance(Duration.ofDays(6))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(PlanKey.BASIC, planOf(studioId), "okres opłacony w cenie BASIC jest w BASIC")
    }

    @Test
    fun `R7 stare odnowienie w FULL oplacone po odnowieniu w BASIC idzie do zwrotu`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(5))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.FULL, endsAt = periodEnd)
        val fullRenewal = renewal(studioId)
        planManagementService.schedulePlanDowngrade(StudioId(studioId), PlanKey.BASIC)
        val basicRenewal = renewal(studioId)
        assertEquals(FULL_PRICE, fullRenewal.amountCents)
        assertEquals(BASIC_PRICE, basicRenewal.amountCents)

        pay(basicRenewal.orderId)
        pay(fullRenewal.orderId)

        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(fullRenewal.orderId))
        assertEquals(periodEnd.plus(Duration.ofDays(30)), endsAtOf(studioId), "okres przedłużony tylko raz")
        assertEquals(listOf("PENDING"), pendingStatuses(studioId), "zamrożony downgrade zostaje")
        clock.advance(Duration.ofDays(10))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(PlanKey.BASIC, planOf(studioId))
    }

    // ── Moduły ───────────────────────────────────────────────────────────────

    @Test
    fun `R2 cofniecie wylaczenia modulu po oplaceniu okresu bez niego jest odrzucane`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val periodEnd = endsAtOf(studioId)!!
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
        val renewal = renewal(studioId)
        assertEquals(BASIC_PRICE, renewal.amountCents)
        pay(renewal.orderId)

        val refused = assertThrows<SubscriptionConflictException> {
            planManagementService.resumeAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
        }
        assertEquals("ADD_ON_RENEWAL_ALREADY_PAID", refused.code)
        assertTrue(!planManagementService.isAddOnResumable(studioRepository.findByStudioId(studioId)!!, periodEnd))
        val buyAgain = assertThrows<ValidationException> {
            checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        }
        assertTrue(buyAgain.message!!.contains("od tego dnia możesz go dokupić"), buyAgain.message)

        clock.advance(Duration.ofDays(6))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId), "okres opłacony bez modułu jest bez modułu")

        // Od daty wyłączenia moduł można dokupić — za resztę opłaconego okresu (29 dni).
        val rebuy = checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        assertEquals(4_737L, rebuy.amountCents)
    }

    @Test
    fun `R6 ponowne wylaczenie modulu nie przesuwa daty wylaczenia`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val periodEnd = endsAtOf(studioId)!!
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
        pay(renewal(studioId).orderId)

        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        assertEquals(periodEnd, cancelAtOf(studioId))
    }

    @Test
    fun `R3 odnowienie wycenione bez modulu, oplacone po jego dokupieniu - modul do konca biezacego okresu`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5)
        val periodEnd = endsAtOf(studioId)!!
        val renewal = renewal(studioId)
        val addOn = checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        pay(addOn.orderId)
        pay(renewal.orderId)

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(renewal.orderId))
        assertEquals(periodEnd, cancelAtOf(studioId), "moduł opłacony do końca bieżącego okresu, nie kolejnego")
        clock.advance(Duration.ofDays(6))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
        assertEquals(periodEnd.plus(Duration.ofDays(30)), endsAtOf(studioId))
    }

    @Test
    fun `R8 odnowienie oplacone z modulem, ktory w miedzyczasie wylaczono - modul zostaje`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val renewal = renewal(studioId)
        assertEquals(BASIC_PRICE + FINANCE_PRICE, renewal.amountCents)
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)

        pay(renewal.orderId)

        assertNull(cancelAtOf(studioId), "zapłacono za moduł w kolejnym okresie")
        clock.advance(Duration.ofDays(6))
        downgradeScheduler.applyDueDowngrades()
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    // ── Stare dopłaty proporcjonalne ─────────────────────────────────────────

    @Test
    fun `R4 upgrade wyceniony do konca okresu, oplacony po odnowieniu - zwrot zamiast FULL na caly kolejny okres`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5)
        val upgrade = checkout(studioId, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))
        pay(renewal(studioId).orderId)

        pay(upgrade.orderId)

        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(upgrade.orderId))
        assertEquals(PlanKey.BASIC, planOf(studioId))
    }

    @Test
    fun `R4 modul wyceniony do konca okresu, oplacony po odnowieniu - dziala tylko do konca tamtego okresu`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5)
        val periodEnd = endsAtOf(studioId)!!
        val addOn = checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        pay(renewal(studioId).orderId)

        pay(addOn.orderId)

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(addOn.orderId))
        assertEquals(periodEnd, cancelAtOf(studioId))
    }

    @Test
    fun `R4 doplata oplacona po koncu okresu idzie do zwrotu`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 1)
        val upgrade = checkout(studioId, CheckoutRequest(type = PaymentOrderType.PLAN_UPGRADE, planKey = PlanKey.FULL))
        clock.advance(Duration.ofDays(2))

        pay(upgrade.orderId)   // np. przelew zaksięgowany po terminie

        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(upgrade.orderId))
        assertEquals(PlanKey.BASIC, planOf(studioId))
    }

    @Test
    fun `odnowienie z modulem oplacone po odnowieniu bez modulu - zwrot, a nie modul za darmo`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 5, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val periodEnd = endsAtOf(studioId)!!
        val withModule = renewal(studioId)                       // 148 zł, karta P24 zostaje otwarta
        planManagementService.cancelAddOn(StudioId(studioId), AddOnKey.FINANCE_MODULE)
        pay(renewal(studioId).orderId)                           // 99 zł — kolejny okres bez modułu

        pay(withModule.orderId)

        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(withModule.orderId))
        assertEquals(periodEnd, cancelAtOf(studioId), "moduł dalej kończy się z opłaconym z nim czasem")
        assertEquals(periodEnd.plus(Duration.ofDays(30)), endsAtOf(studioId))
    }

    @Test
    fun `modul oplacony juz po koncu wycenionego okresu - zwrot, nawet gdy okres przedluzono`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 2)
        val addOn = checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        pay(renewal(studioId).orderId)
        clock.advance(Duration.ofDays(3))                        // przelew zaksięgowany po terminie

        pay(addOn.orderId)

        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(addOn.orderId))
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
    }

    @Test
    fun `doplata zaplacona przed koncem okresu przechodzi, choc realizacja przyszla po nim`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 1)
        val periodEnd = endsAtOf(studioId)!!
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 163, planKey = PlanKey.BASIC,
            addOns = listOf(AddOnKey.FINANCE_MODULE), status = PaymentOrderStatus.PAID, p24OrderId = 77_001)
        jdbc.update("UPDATE payment_orders SET priced_until = ? WHERE id = ?", Timestamp.from(periodEnd), order.id)
        clock.advance(Duration.ofDays(1).plusHours(1))
        lifecycleJob.advanceDueSubscriptions()
        assertEquals(SubscriptionStatus.PAST_DUE, statusOf(studioId))

        fulfillmentService.fulfillIfPaid(order.id)

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id), "zapłata przyszła przed końcem okresu")
    }

    @Test
    fun `darmowy modul z trialu realizowany po zakupie pakietu jest anulowany, nie zwracany`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = clock.instant().plus(Duration.ofDays(30)))
        val free = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 0, planKey = PlanKey.BASIC,
            addOns = listOf(AddOnKey.FINANCE_MODULE), status = PaymentOrderStatus.PAID)

        fulfillmentService.fulfillIfPaid(free.id)

        assertEquals(PaymentOrderStatus.CANCELLED, orderStatus(free.id))
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
        assertEquals(0.0, counter("payments.refund.required"))
    }

    // ── Trial i karencja ─────────────────────────────────────────────────────

    @Test
    fun `R5 zakup pakietu w trialu - reszta trialu nie jest liczona do doplat`() {
        val studioId = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = clock.instant().plus(Duration.ofDays(59)))
        pay(checkout(studioId, CheckoutRequest(type = PaymentOrderType.INITIAL_PURCHASE, planKey = PlanKey.BASIC)).orderId)
        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId))

        val addOn = checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))
        val upgrade = planManagementService.previewPlanChange(StudioId(studioId), PlanKey.FULL)

        assertEquals(FINANCE_PRICE, addOn.amountCents, "moduł na opłacony miesiąc, nie na 89 dni")
        assertEquals(FULL_PRICE - BASIC_PRICE, upgrade.proratedAmountCents)
        assertEquals(30L, upgrade.daysRemaining)
    }

    @Test
    fun `R10 odnowienie minute po koncu karencji rozlicza wykorzystana karencje`() {
        val periodEnd = clock.instant().plus(Duration.ofDays(1))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = periodEnd)
        clock.advance(Duration.ofDays(8).plusMinutes(1))
        lifecycleJob.advanceDueSubscriptions()
        assertEquals(SubscriptionStatus.EXPIRED, statusOf(studioId))

        pay(renewal(studioId).orderId)

        // 7 dni karencji z pełnym dostępem jest częścią opłaconego okresu: 37 dni za cenę 30 — nie.
        assertEquals(clock.instant().minus(Duration.ofDays(7)).plus(Duration.ofDays(30)), endsAtOf(studioId))
    }

    @Test
    fun `po dlugiej przerwie odnowienie liczy okres od zaplaty`() {
        val studioId = studioWithPlan(SubscriptionStatus.EXPIRED, PlanKey.BASIC, endsAt = clock.instant().minus(Duration.ofDays(60)))

        pay(renewal(studioId).orderId)

        assertEquals(clock.instant().plus(Duration.ofDays(30)), endsAtOf(studioId))
    }

    // ── Proporcja co do sekundy ──────────────────────────────────────────────

    @Test
    fun `doplata liczona co do sekundy, a nie z dni zaokraglonych w dol`() {
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC,
            endsAt = clock.instant().plus(Duration.ofDays(29).plusHours(23)))

        val addOn = checkout(studioId, CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE)))

        // 4900 × (29 d 23 h / 30 d) = 4893,19 → 4893 (dawniej 4900 × 29/30 = 4737).
        assertEquals(4_893L, addOn.amountCents)
        assertTrue(addOn.description.contains("30 dni"), addOn.description)
    }
}
