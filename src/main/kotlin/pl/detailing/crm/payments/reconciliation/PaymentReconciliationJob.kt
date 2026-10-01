package pl.detailing.crm.payments.reconciliation

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.payments.checkout.OrderFulfillmentService
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
 *  4. jednorazowo sprawdza zamówienia wygaszone bez pytania P24 (zastąpione nowszym).
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
        val expiredUnchecked = orderRepository.findUncheckedExpiredIds(
            createdAfter = now.minus(EXPIRED_LOOKBACK),
            pageable = PageRequest.of(0, P24_LOOKUPS_PER_RUN)
        )
        var lookups = 0
        for (orderId in pending + expiredUnchecked) {
            if (accessPolicy.now().isAfter(deadline) || lookups >= P24_LOOKUPS_PER_RUN) return
            lookups++
            runStep("rekoncyliacja zamówienia $orderId") { reconcileOrder(orderId, now) }
        }
    }

    private fun reconcileOrder(orderId: UUID, now: Instant) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val canAskGateway = properties.isConfigured && !properties.mockMode

        val state = if (canAskGateway) p24Client.getTransactionBySessionId(order.sessionId) else null
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
            recovered.increment()
            logger.warn("Rekoncyliacja: zamówienie {} opłacone w P24 bez obsłużonej notyfikacji — domykam", orderId)
            processor.process(notificationId)
            tx.executeWithoutResult { orderRepository.lockById(orderId)?.lastReconciledAt = now }
            return
        }

        tx.executeWithoutResult {
            val locked = orderRepository.lockById(orderId) ?: return@executeWithoutResult
            locked.lastReconciledAt = now
            val expiresAfter = locked.createdAt.plus(Duration.ofMinutes(properties.orderExpiryMinutes))
            if (locked.status == PaymentOrderStatus.PENDING && !now.isBefore(expiresAfter)) {
                locked.expire(
                    if (canAskGateway) "Brak płatności w czasie ważności transakcji (stan P24: ${state?.status ?: "brak transakcji"})"
                    else "Brak płatności w czasie ważności zamówienia"
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
        private const val BATCH_SIZE = 50
        private const val P24_LOOKUPS_PER_RUN = 10
    }
}
