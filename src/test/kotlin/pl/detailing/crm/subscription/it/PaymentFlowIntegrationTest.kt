package pl.detailing.crm.subscription.it

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.detailing.crm.payments.checkout.CheckoutRequest
import pl.detailing.crm.payments.checkout.CheckoutService
import pl.detailing.crm.payments.notification.NotificationOutcome
import pl.detailing.crm.payments.notification.PaymentNotificationSource
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.payments.p24.Przelewy24Properties
import pl.detailing.crm.payments.reconciliation.PaymentReconciliationJob
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.shared.PaymentsUnavailableException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionConflictException
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Duration
import java.util.UUID

/**
 * Droga pieniędzy od kliknięcia „Kup" do efektu, z awariami, które zdarzają się naprawdę:
 * zgubiona notyfikacja, notyfikacja przed zamówieniem, P24 niedostępne przy weryfikacji,
 * spóźniona płatność za porzucone zamówienie, druga płatność za to samo, błąd realizacji.
 *
 * Zasada, którą sprawdza każdy test: pieniądze, które przyszły, zawsze zostawiają ślad
 * (PAID / FULFILLED / REFUND_REQUIRED / notyfikacja do przeglądu) — nigdy nie znikają,
 * a efekt (dni, plan, moduł) powstaje dokładnie raz.
 */
class PaymentFlowIntegrationTest : SubscriptionIntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SubscriptionItDatabase.register(registry, "payment_flow")
    }

    @Autowired lateinit var rolePreviewGuard: RolePreviewOutboundGuard

    private fun financeRequest() = CheckoutRequest(type = PaymentOrderType.ADD_ON_PURCHASE, addOnKeys = listOf(AddOnKey.FINANCE_MODULE))

    private fun notificationRow(sessionId: String): Map<String, Any?> =
        jdbc.queryForMap("SELECT status, source, attempts, order_id, last_error FROM payment_notifications WHERE session_id = ?", sessionId)

    // ── Ścieżka szczęśliwa ───────────────────────────────────────────────────

    @Test
    fun `zakup modulu - rejestracja w P24, notyfikacja, realizacja i wpis w historii z numerem zamowienia`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 15)

        val response = checkoutService.checkout(StudioId(studioId), BUYER, financeRequest())

        assertEquals(PaymentOrderStatus.PENDING, response.status)
        val order = orderRepository.findById(response.orderId).orElseThrow()
        assertEquals("https://sandbox.przelewy24.pl/trnRequest/TOKEN-${order.sessionId}", response.paymentUrl)
        assertEquals(2_450L, response.amountCents, "4900 gr × 15/30 dni")

        assertEquals(NotificationOutcome.FULFILLED, payAndNotify(order))

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
        assertEquals(1, gateway.count(HttpMethod.PUT, "/api/v1/transaction/verify"))
        assertEquals(1L, jdbc.queryForObject(
            "SELECT count(*) FROM subscription_payment_log WHERE order_id = ? AND event_type = 'ADD_ON_ACTIVATION'", Long::class.java, order.id))
        assertEquals("PROCESSED", notificationRow(order.sessionId)["status"])
    }

    @Test
    fun `ponowienie notyfikacji po realizacji nie wola verify drugi raz`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = orderRepository.findById(checkoutService.checkout(StudioId(studioId), BUYER, financeRequest()).orderId).orElseThrow()
        val notification = signedNotification(order.sessionId, order.amountCents, gateway.pay(order.sessionId, order.amountCents))
        notificationProcessor.process(notificationProcessor.record(notification))

        // P24 ponawia notyfikację (np. nie doczekało się odpowiedzi).
        val outcome = notificationProcessor.process(notificationProcessor.record(notification))

        assertEquals(NotificationOutcome.DUPLICATE, outcome)
        assertEquals(1, gateway.count(HttpMethod.PUT, "/api/v1/transaction/verify"))
        assertEquals(1L, ledgerCount(studioId, "ADD_ON_ACTIVATION"))
    }

    // ── Odrzucenia i przegląd ręczny ─────────────────────────────────────────

    @Test
    fun `notyfikacja z inna kwota jest odrzucana bez weryfikacji w P24`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = orderRepository.findById(checkoutService.checkout(StudioId(studioId), BUYER, financeRequest()).orderId).orElseThrow()

        val outcome = notificationProcessor.process(notificationProcessor.record(
            signedNotification(order.sessionId, order.amountCents - 1, gateway.pay(order.sessionId))))

        assertEquals(NotificationOutcome.REJECTED, outcome)
        assertEquals(0, gateway.count(HttpMethod.PUT, "/api/v1/transaction/verify"), "nie rozliczamy w P24 pieniędzy, których nie przyjmiemy")
        assertEquals(PaymentOrderStatus.PENDING, orderStatus(order.id))
        assertEquals(emptySet<AddOnKey>(), addOnsOf(studioId))
    }

    @Test
    fun `druga platnosc za oplacone zamowienie trafia do przegladu i nie daje drugiego efektu`() {
        val endsAt = clock.instant().plus(Duration.ofDays(10))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = endsAt)
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC)
        payAndNotify(order)

        // Ta sama sesja opłacona drugi raz (inny orderId P24) — np. powrót do starej karty płatności.
        val secondPayment = notificationProcessor.process(notificationProcessor.record(
            signedNotification(order.sessionId, order.amountCents, 4_242_424L)))

        assertEquals(NotificationOutcome.NEEDS_REVIEW, secondPayment)
        assertEquals(endsAt.plus(Duration.ofDays(30)), endsAtOf(studioId))
        assertEquals(1L, ledgerCount(studioId, "SUBSCRIPTION_RENEWAL"))
        assertEquals(1.0, counter("payments.notifications.second.payment"))
    }

    // ── Kolejność zdarzeń i awarie P24 ───────────────────────────────────────

    @Test
    fun `notyfikacja przed zamowieniem czeka i zostaje dopasowana przy ponowieniu`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val sessionId = "CRM-${UUID.randomUUID()}"
        val p24OrderId = gateway.pay(sessionId, BASIC_PRICE)

        assertEquals(NotificationOutcome.UNMATCHED,
            notificationProcessor.process(notificationProcessor.record(signedNotification(sessionId, BASIC_PRICE, p24OrderId))))
        assertEquals("UNMATCHED", notificationRow(sessionId)["status"])

        // Zamówienie pojawia się później (np. replika bazy, wolny zapis).
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC, sessionId = sessionId)
        paymentReconciliationJob.reconcile()
        assertEquals(PaymentOrderStatus.PENDING, orderStatus(order.id), "ponowienie dopasowania ma odstęp — jeszcze nie teraz")

        clock.advance(Duration.ofMinutes(6))
        paymentReconciliationJob.reconcile()

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals("PROCESSED", notificationRow(sessionId)["status"])
    }

    @Test
    fun `nieudana weryfikacja w P24 planuje ponowienie, a zamowienie czeka`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_000, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE))
        gateway.verifyFails = true

        assertEquals(NotificationOutcome.RETRY_SCHEDULED, payAndNotify(order))
        assertEquals(PaymentOrderStatus.PENDING, orderStatus(order.id))
        assertEquals(1, (notificationRow(order.sessionId)["attempts"] as Number).toInt())

        gateway.verifyFails = false
        clock.advance(Duration.ofMinutes(2))
        paymentReconciliationJob.reconcile()

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    @Test
    fun `ponowienie od P24 przed terminem ponowienia nie wola verify i nie zuzywa prob`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_000, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val notification = signedNotification(order.sessionId, order.amountCents, gateway.pay(order.sessionId, order.amountCents))
        gateway.verifyFails = true
        notificationProcessor.process(notificationProcessor.record(notification))

        // P24 ponawia notyfikację, zanim minął termin naszego ponowienia (minuta po pierwszej próbie).
        val resend = notificationProcessor.process(notificationProcessor.record(notification))

        assertEquals(NotificationOutcome.RETRY_SCHEDULED, resend)
        assertEquals(1, gateway.count(HttpMethod.PUT, "/api/v1/transaction/verify"))
        assertEquals(1, (notificationRow(order.sessionId)["attempts"] as Number).toInt())
    }

    @Test
    fun `wielogodzinna awaria verify nie konczy sie stanem koncowym - platnosc domyka sie po naprawie`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_000, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE))
        gateway.verifyFails = true
        payAndNotify(order)

        repeat(14) {
            clock.advance(Duration.ofMinutes(61))
            paymentReconciliationJob.reconcile()
        }

        assertEquals("RECEIVED", notificationRow(order.sessionId)["status"], "nadal czeka — nie trafia do przeglądu na zawsze")
        assertTrue(counter("payments.notifications.retry.long") > 0.0, "długie ponawianie jest alarmem")
        assertEquals(PaymentOrderStatus.EXPIRED, orderStatus(order.id), "zamówienie wygasło, ale przyjmie płatność")

        gateway.verifyFails = false
        clock.advance(Duration.ofMinutes(61))
        paymentReconciliationJob.reconcile()

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    @Test
    fun `rekoncyliacja nie liczy odrzuconej platnosci jako odzyskanej i wygasza zamowienie`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_000, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE),
            token = "TOKEN-x")
        gateway.pay(order.sessionId, 2_999)   // w P24 zapłacono inną kwotę

        clock.advance(Duration.ofMinutes(31))
        paymentReconciliationJob.reconcile()

        assertEquals("REJECTED", notificationRow(order.sessionId)["status"])
        assertEquals(PaymentOrderStatus.EXPIRED, orderStatus(order.id))
        assertEquals(0.0, counter("payments.reconciliation.recovered.payments"))
    }

    @Test
    fun `bez bramki porzucone zamowienie wygasa nieoznaczone, a po przywroceniu poswiadczen jest sprawdzane`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_000, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE),
            token = "TOKEN-x")
        gateway.pay(order.sessionId, order.amountCents)   // kupujący zapłacił w trakcie przerwy w konfiguracji
        val blindJob = PaymentReconciliationJob(notificationRepository, orderRepository, notificationProcessor, fulfillmentService,
            p24Client, Przelewy24Properties(), accessPolicy, meterRegistry, transactionManager)

        clock.advance(Duration.ofMinutes(31))
        blindJob.reconcile()
        assertEquals(PaymentOrderStatus.EXPIRED, orderStatus(order.id))
        assertNull(jdbc.queryForObject("SELECT last_reconciled_at FROM payment_orders WHERE id = ?", java.sql.Timestamp::class.java, order.id))
        assertEquals(0, gateway.count(HttpMethod.GET, "/api/v1/transaction/by/sessionId/"))

        clock.advance(Duration.ofHours(1))
        paymentReconciliationJob.reconcile()   // poświadczenia wróciły

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
    }

    @Test
    fun `odrzucony verify przy transakcji juz zweryfikowanej w P24 nie blokuje realizacji`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 3_000, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE))
        val p24OrderId = gateway.pay(order.sessionId, order.amountCents)
        gateway.transactions.getValue(order.sessionId).status = 2   // zweryfikowana wcześniej (np. przed restartem)
        gateway.verifyFails = true

        val outcome = notificationProcessor.process(notificationProcessor.record(signedNotification(order.sessionId, order.amountCents, p24OrderId)))

        assertEquals(NotificationOutcome.FULFILLED, outcome)
        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
    }

    @Test
    fun `zgubiona notyfikacja - rekoncyliacja znajduje platnosc w P24 i realizuje zamowienie`() {
        val endsAt = clock.instant().plus(Duration.ofDays(3))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = endsAt)
        val order = orderRepository.findById(
            checkoutService.checkout(StudioId(studioId), BUYER, CheckoutRequest(type = PaymentOrderType.RENEWAL)).orderId
        ).orElseThrow()
        gateway.pay(order.sessionId, order.amountCents)   // zapłacone, webhook nigdy nie dotarł

        clock.advance(Duration.ofMinutes(10))
        paymentReconciliationJob.reconcile()
        assertEquals(PaymentOrderStatus.PENDING, orderStatus(order.id), "za wcześnie — notyfikacja może jeszcze przyjść")

        clock.advance(Duration.ofMinutes(11))
        paymentReconciliationJob.reconcile()

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(endsAt.plus(Duration.ofDays(30)), endsAtOf(studioId))
        assertEquals(PaymentNotificationSource.RECONCILIATION.name, notificationRow(order.sessionId)["source"])
        assertEquals(1.0, counter("payments.reconciliation.recovered.payments"))
    }

    @Test
    fun `porzucone zamowienie wygasa, a spozniona platnosc i tak jest przyjeta`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val order = orderRepository.findById(checkoutService.checkout(StudioId(studioId), BUYER, financeRequest()).orderId).orElseThrow()

        clock.advance(Duration.ofMinutes(31))
        paymentReconciliationJob.reconcile()
        assertEquals(PaymentOrderStatus.EXPIRED, orderStatus(order.id))
        assertNotNull(jdbc.queryForObject("SELECT last_reconciled_at FROM payment_orders WHERE id = ?", java.sql.Timestamp::class.java, order.id))

        // Kupujący jednak zapłacił (np. przelew tradycyjny zaksięgowany po czasie).
        assertEquals(NotificationOutcome.FULFILLED, payAndNotify(order))
        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    @Test
    fun `nowy checkout po wygasnieciu tokenu zaklada nowe zamowienie i wygasza stare`() {
        val studioId = activeStudio(PlanKey.BASIC)
        val first = checkoutService.checkout(StudioId(studioId), BUYER, financeRequest())

        clock.advance(Duration.ofMinutes(15))
        val second = checkoutService.checkout(StudioId(studioId), BUYER, financeRequest())

        assertTrue(first.orderId != second.orderId, "token P24 pierwszego zamówienia już nie działa")
        assertEquals(PaymentOrderStatus.EXPIRED, orderStatus(first.orderId))
        assertEquals(PaymentOrderStatus.PENDING, orderStatus(second.orderId))
    }

    @Test
    fun `drugie klikniecie w trakcie rejestracji pierwszego czeka, a nie zastepuje go`() {
        val studioId = activeStudio(PlanKey.BASIC, daysLeft = 15)
        // Pierwsze kliknięcie: zamówienie zapisane, rejestracja w P24 jeszcze trwa (brak tokenu).
        val inFlight = order(studioId, PaymentOrderType.ADD_ON_PURCHASE, 2_450, planKey = PlanKey.BASIC, addOns = listOf(AddOnKey.FINANCE_MODULE))

        val conflict = assertThrows<SubscriptionConflictException> { checkoutService.checkout(StudioId(studioId), BUYER, financeRequest()) }
        assertEquals("CHECKOUT_IN_PROGRESS", conflict.code)
        assertEquals(PaymentOrderStatus.PENDING, orderStatus(inFlight.id))

        // Rejestracja nigdy się nie skończyła (np. restart w jej trakcie) — po minucie nowa próba przechodzi.
        clock.advance(Duration.ofMinutes(2))
        val retry = checkoutService.checkout(StudioId(studioId), BUYER, financeRequest())
        assertEquals(PaymentOrderStatus.EXPIRED, orderStatus(inFlight.id))
        assertNotNull(retry.paymentUrl)
    }

    @Test
    fun `blad realizacji zostawia zamowienie PAID, a rekoncyliacja je konczy`() {
        val endsAt = clock.instant().plus(Duration.ofDays(3))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = endsAt)
        val order = order(studioId, PaymentOrderType.RENEWAL, BASIC_PRICE, planKey = PlanKey.BASIC)
        poisonWrites("studios", "id", studioId)

        assertEquals(NotificationOutcome.PAID_AWAITING_FULFILLMENT, payAndNotify(order))
        assertEquals(PaymentOrderStatus.PAID, orderStatus(order.id), "fakt płatności nie cofa się razem z realizacją")
        assertEquals(endsAt, endsAtOf(studioId))
        assertEquals("PROCESSED", notificationRow(order.sessionId)["status"])

        dropPoisonTriggers()
        clock.advance(Duration.ofMinutes(2))
        paymentReconciliationJob.reconcile()

        assertEquals(PaymentOrderStatus.FULFILLED, orderStatus(order.id))
        assertEquals(endsAt.plus(Duration.ofDays(30)), endsAtOf(studioId))
        assertEquals(1L, ledgerCount(studioId, "SUBSCRIPTION_RENEWAL"))
    }

    @Test
    fun `zamowienie startowe oplacone przez studio juz oplacone konczy sie zwrotem`() {
        val endsAt = clock.instant().plus(Duration.ofDays(20))
        val studioId = studioWithPlan(SubscriptionStatus.ACTIVE, PlanKey.BASIC, endsAt = endsAt)
        val order = order(studioId, PaymentOrderType.INITIAL_PURCHASE, FULL_PRICE, planKey = PlanKey.FULL)

        assertEquals(NotificationOutcome.REFUND_REQUIRED, payAndNotify(order))

        assertEquals(PaymentOrderStatus.REFUND_REQUIRED, orderStatus(order.id))
        assertEquals(PlanKey.BASIC, planOf(studioId))
        assertEquals(endsAt, endsAtOf(studioId))
        assertNotNull(jdbc.queryForObject("SELECT failure_reason FROM payment_orders WHERE id = ?", String::class.java, order.id))
    }

    // ── Konfiguracja bramki ──────────────────────────────────────────────────

    private fun checkoutWith(properties: Przelewy24Properties) = CheckoutService(
        properties, p24Client, orderRepository, fulfillmentService, studioRepository, entitlementService, prorationService,
        pricingService, planManagementService, planRepository, addOnRepository, accessPolicy, rolePreviewGuard, transactionManager
    )

    @Test
    fun `bez poswiadczen P24 i bez jawnego mocka checkout odpowiada 503 i nie zaklada zamowienia`() {
        val studioId = studio(SubscriptionStatus.EXPIRED, endsAt = clock.instant().minus(Duration.ofDays(40)))

        assertThrows<PaymentsUnavailableException> {
            checkoutWith(Przelewy24Properties()).checkout(StudioId(studioId), BUYER,
                CheckoutRequest(type = PaymentOrderType.INITIAL_PURCHASE, planKey = PlanKey.FULL))
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM payment_orders", Long::class.java))
        assertNull(planOf(studioId), "żadnego pakietu za darmo")
        assertEquals(SubscriptionStatus.EXPIRED, statusOf(studioId))
    }

    @Test
    fun `jawny tryb mock realizuje zamowienie bez bramki`() {
        val studioId = studio(SubscriptionStatus.NO_PLAN)

        val response = checkoutWith(Przelewy24Properties(mockMode = true)).checkout(StudioId(studioId), BUYER,
            CheckoutRequest(type = PaymentOrderType.INITIAL_PURCHASE, planKey = PlanKey.FULL))

        assertEquals(PaymentOrderStatus.FULFILLED, response.status)
        assertNull(response.paymentUrl)
        assertEquals(PlanKey.FULL, planOf(studioId))
        assertEquals(SubscriptionStatus.ACTIVE, statusOf(studioId))
        assertEquals(0, gateway.calls.size)
    }

    @Test
    fun `modul w trialu jest darmowy i nie wymaga bramki nawet bez poswiadczen`() {
        val studioId = studioWithPlan(SubscriptionStatus.TRIALING, PlanKey.BASIC, trialEndsAt = clock.instant().plus(Duration.ofDays(30)))

        val response = checkoutWith(Przelewy24Properties()).checkout(StudioId(studioId), BUYER, financeRequest())

        assertEquals(0L, response.amountCents)
        assertEquals(PaymentOrderStatus.FULFILLED, response.status)
        assertEquals(setOf(AddOnKey.FINANCE_MODULE), addOnsOf(studioId))
    }

    @Test
    fun `awaria rejestracji w P24 - 503, zamowienie FAILED, kolejna proba zaklada nowe`() {
        val studioId = activeStudio(PlanKey.BASIC)
        gateway.registerFails = true

        assertThrows<PaymentsUnavailableException> { checkoutService.checkout(StudioId(studioId), BUYER, financeRequest()) }
        assertEquals(listOf("FAILED"), jdbc.queryForList("SELECT status FROM payment_orders", String::class.java))

        gateway.registerFails = false
        val retry = checkoutService.checkout(StudioId(studioId), BUYER, financeRequest())
        assertEquals(PaymentOrderStatus.PENDING, retry.status)
        assertNotNull(retry.paymentUrl)
    }
}
