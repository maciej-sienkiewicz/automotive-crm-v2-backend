package pl.detailing.crm.payments.reconciliation

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.payments.checkout.OrderFulfillmentService
import pl.detailing.crm.payments.notification.NotificationOutcome
import pl.detailing.crm.payments.notification.PaymentNotificationProcessor
import pl.detailing.crm.payments.notification.PaymentNotificationRepository
import pl.detailing.crm.payments.notification.PaymentNotificationSource
import pl.detailing.crm.payments.order.PaymentOrderRepository
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.payments.p24.Przelewy24Properties
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Domyka płatności, których nie domknęła notyfikacja. Wcześniej `SubscriptionReconciliationJob`
 * tylko LICZYŁ zamówienia wiszące w PENDING — zgubiona notyfikacja zostawała incydentem do
 * ręcznego wyjaśnienia, a porzucone koszyki wisiały wiecznie i zapalały ten sam alarm (audyt, P5).
 *
 * Co 5 minut, w tej kolejności:
 *  1. ponawia zapisane notyfikacje, które czekają (błąd przejściowy, nieznana sesja),
 *  2. dokańcza realizację zamówień PAID (pieniądze są, efektu jeszcze nie ma),
 *  3. pyta API P24 o stan zamówień PENDING starszych niż `p24.reconcile-after-minutes`:
 *     opłacone → ta sama ścieżka co notyfikacja (inbox, `source = RECONCILIATION`);
 *     nieopłacone po `p24.order-expiry-minutes` → EXPIRED (spóźniona płatność nadal przejdzie),
 *  4. sprawdza zamówienia wygaszone z wydaną stroną płatności: niesprawdzone (zastąpione
 *     nowszym, wygaszone przy wyłączonej bramce) od razu, sprawdzone — co 6 godzin przez tydzień.
 *
 * Bez skonfigurowanej bramki (brak poświadczeń, mock) nikogo nie pyta: porzucone zamówienia
 * wygasa, ale nie oznacza jako sprawdzonych, więc po przywróceniu poświadczeń trafią do kroku 4.
 *
 * Wszystkie `@Scheduled` w tej aplikacji biegną na JEDNYM wątku, więc przebieg ma budżet
 * czasu i limit zapytań do P24 — wolne P24 nie może wstrzymać pozostałych jobów na minuty.
 */
@Component
class PaymentReconciliationJob(
    private val notificationRepository: PaymentNotificationRepository,
    private val orderRepository: PaymentOrderRepository,
    private val processor: PaymentNotificationProcessor,
    private val fulfillmentService: OrderFulfillmentService,
    private val p24Client: Przelewy24Client,
    private val properties: Przelewy24Properties,
    private val accessPolicy: SubscriptionAccessPolicy,
    meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val failures = meterRegistry.counter("payments.reconciliation.failures")
    private val recovered = meterRegistry.counter("payments.reconciliation.recovered.payments")

    @Scheduled(cron = "0 2/5 * * * *")
    fun reconcile() {
        val started = accessPolicy.now()
        val deadline = started.plus(TIME_BUDGET)

        retryNotifications(started, deadline)
        completePaidOrders(started, deadline)
        reconcilePendingOrders(started, deadline)
    }

    private fun retryNotifications(now: Instant, deadline: Instant) {
        for (id in notificationRepository.findDueForRetry(now, PageRequest.of(0, BATCH_SIZE))) {
            if (accessPolicy.now().isAfter(deadline)) return
            runStep("ponowienie notyfikacji $id") { processor.process(id) }
        }
    }

    private fun completePaidOrders(now: Instant, deadline: Instant) {
        val paidBefore = now.minus(PAID_GRACE)
        for (orderId in orderRepository.findPaidUnfulfilledIds(paidBefore, PageRequest.of(0, BATCH_SIZE))) {
            if (accessPolicy.now().isAfter(deadline)) return
            runStep("realizacja opłaconego zamówienia $orderId") { fulfillmentService.fulfillIfPaid(orderId) }
        }
    }

    private fun reconcilePendingOrders(now: Instant, deadline: Instant) {
        val pending = orderRepository.findStalePendingIds(
            createdBefore = now.minus(Duration.ofMinutes(properties.reconcileAfterMinutes)),
            reconciledBefore = now.minus(RECHECK_INTERVAL),
            pageable = PageRequest.of(0, P24_LOOKUPS_PER_RUN)
        )
        val canAskGateway = properties.isConfigured && !properties.mockMode
        if (!canAskGateway) {
            // Bez bramki nie da się sprawdzić, czy zamówienie jest opłacone. Porzucone zamówienia
            // wygasają, ale NIE są oznaczane jako sprawdzone — po przywróceniu poświadczeń każde
            // zostanie raz zapytane w P24 (płatność z czasu przerwy nie może zginąć po cichu).
            for (orderId in pending) {
                if (accessPolicy.now().isAfter(deadline)) return
                runStep("wygaszenie zamówienia $orderId bez bramki") { expireIfAbandoned(orderId, now, gatewayState = null, checked = false) }
            }
            return
        }

        val expiredToCheck = orderRepository.findExpiredDueForCheck(
            createdAfter = now.minus(EXPIRED_LOOKBACK),
            reconciledBefore = now.minus(EXPIRED_RECHECK_INTERVAL),
            pageable = PageRequest.of(0, P24_LOOKUPS_PER_RUN)
        )
        var lookups = 0
        for (orderId in pending + expiredToCheck) {
            if (accessPolicy.now().isAfter(deadline) || lookups >= P24_LOOKUPS_PER_RUN) {
                logger.info("Rekoncyliacja: limit zapytań do P24 albo czasu wyczerpany — reszta w kolejnym przebiegu")
                return
            }
            lookups++
            runStep("rekoncyliacja zamówienia $orderId") { reconcileOrder(orderId, now) }
        }
    }

    private fun reconcileOrder(orderId: UUID, now: Instant) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val state = p24Client.getTransactionBySessionId(order.sessionId)

        if (state != null && (state.status == PaymentNotificationProcessor.P24_STATUS_PAID ||
                    state.status == PaymentNotificationProcessor.P24_STATUS_VERIFIED)) {
            // Opłacone, a notyfikacji nie ma — ta sama ścieżka co notyfikacja (inbox, idempotencja po orderId P24).
            val notificationId = processor.record(
                Przelewy24Client.P24Notification(
                    merchantId = properties.merchantId,
                    posId = properties.posId,
                    sessionId = state.sessionId.ifBlank { order.sessionId },
                    amount = state.amount,
                    originAmount = state.amount,
                    currency = state.currency.ifBlank { order.currency },
                    orderId = state.orderId
                ),
                source = PaymentNotificationSource.RECONCILIATION,
                alreadyVerified = state.status == PaymentNotificationProcessor.P24_STATUS_VERIFIED
            )
            val outcome = processor.process(notificationId)
            if (outcome in SETTLED_OUTCOMES) {
                recovered.increment()
                logger.warn("Rekoncyliacja: zamówienie {} opłacone w P24 bez obsłużonej notyfikacji — domknięte ({})", orderId, outcome)
                tx.executeWithoutResult { orderRepository.lockById(orderId)?.lastReconciledAt = now }
                return
            }
            // Odrzucona (inna kwota), do przeglądu albo czeka na ponowienie: zamówienie nie jest
            // opłacone. Dalej jak nieopłacone — sprawdzone i po czasie wygaszone — zamiast wracać
            // w każdym przebiegu i zjadać limit zapytań innym zamówieniom.
            logger.warn("Rekoncyliacja: płatność P24 za zamówienie {} nierozstrzygnięta ({})", orderId, outcome)
        }
        expireIfAbandoned(orderId, now, gatewayState = state?.status, checked = true)
    }

    private fun expireIfAbandoned(orderId: UUID, now: Instant, gatewayState: Int?, checked: Boolean) {
        tx.executeWithoutResult {
            val locked = orderRepository.lockById(orderId) ?: return@executeWithoutResult
            if (checked) locked.lastReconciledAt = now
            val expiresAfter = locked.createdAt.plus(Duration.ofMinutes(properties.orderExpiryMinutes))
            if (locked.status == PaymentOrderStatus.PENDING && !now.isBefore(expiresAfter)) {
                locked.expire(
                    if (checked) "Brak płatności w czasie ważności transakcji (stan P24: ${gatewayState ?: "brak transakcji"})"
                    else "Brak płatności w czasie ważności zamówienia (bramka P24 nieskonfigurowana — do sprawdzenia)"
                )
                logger.info("Zamówienie {} wygaszone (brak płatności)", orderId)
            }
        }
    }

    private fun runStep(what: String, step: () -> Unit) {
        try {
            step()
        } catch (e: Exception) {
            failures.increment()
            logger.error("Rekoncyliacja płatności: {} nie powiodła się — ponowienie w kolejnym przebiegu", what, e)
        }
    }

    companion object {
        private val TIME_BUDGET: Duration = Duration.ofSeconds(30)
        private val PAID_GRACE: Duration = Duration.ofMinutes(1)
        private val RECHECK_INTERVAL: Duration = Duration.ofMinutes(10)
        private val EXPIRED_LOOKBACK: Duration = Duration.ofDays(7)
        /** Wygaszone zamówienie z wydaną stroną płatności sprawdzane ponownie co tyle (spóźnione przelewy). */
        private val EXPIRED_RECHECK_INTERVAL: Duration = Duration.ofHours(6)
        private val SETTLED_OUTCOMES = setOf(
            NotificationOutcome.FULFILLED, NotificationOutcome.PAID_AWAITING_FULFILLMENT,
            NotificationOutcome.REFUND_REQUIRED, NotificationOutcome.DUPLICATE
        )
        private const val BATCH_SIZE = 50
        private const val P24_LOOKUPS_PER_RUN = 10
    }
}
