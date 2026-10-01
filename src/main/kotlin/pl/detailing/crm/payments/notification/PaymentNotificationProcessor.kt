package pl.detailing.crm.payments.notification

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.payments.checkout.FulfillmentOutcome
import pl.detailing.crm.payments.checkout.OrderFulfillmentService
import pl.detailing.crm.payments.order.PaymentOrderRepository
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import java.time.Duration
import java.util.UUID

/** Jak skończyła się obsługa jednej notyfikacji — dla logów, mierników i testów. */
enum class NotificationOutcome {
    /** Płatność przyjęta, zamówienie zrealizowane. */
    FULFILLED,
    /** Płatność przyjęta, efektu nie da się zastosować — zamówienie czeka na zwrot. */
    REFUND_REQUIRED,
    /** Płatność przyjęta (PAID), realizacja nie powiodła się — ponowi worker. */
    PAID_AWAITING_FULFILLMENT,
    /** Ta sama płatność już obsłużona — nic do zrobienia (bez ponownego `verify`). */
    DUPLICATE,
    /** Brak zamówienia o tej sesji — ponowienie dopasowania później. */
    UNMATCHED,
    /** Kwota lub waluta niezgodna z zamówieniem — odrzucona bez weryfikacji w P24. */
    REJECTED,
    /** Wymaga człowieka (druga płatność za opłacone zamówienie, wyczerpane ponowienia). */
    NEEDS_REVIEW,
    /** Błąd przejściowy (np. P24 niedostępne) — ponowienie zaplanowane. */
    RETRY_SCHEDULED
}

/**
 * Obsługa notyfikacji płatności Przelewy24 — idempotentna i odporna na kolejność zdarzeń.
 *
 * Wcześniej webhook wołał `verify` w P24, ZANIM sprawdził stan zamówienia, a potem
 * `completeOrder` sprawdzał „czy już PAID?" bez blokady i bez wersji. Duplikat notyfikacji
 * przyjęty równolegle realizował zamówienie dwa razy (+60 dni zamiast +30 — odtworzone na
 * Postgresie), duplikat po fakcie wołał `verify` drugi raz, a zamówienie w innym stanie niż
 * PENDING kończyło się pieniędzmi rozliczonymi w P24 i odmową realizacji (audyt, P1, P2).
 *
 * Teraz obsługa ma cztery kroki:
 *  1. DECYZJA (krótka transakcja, blokady: notyfikacja → zamówienie, bez HTTP): duplikat
 *     opłaconej płatności kończy się tu — bez `verify`; niezgodna kwota → REJECTED bez
 *     `verify` (nie rozliczamy w P24 pieniędzy, których nie przyjmiemy); brak zamówienia →
 *     UNMATCHED i ponowienie.
 *  2. WERYFIKACJA w P24 — poza transakcją bazy (timeouty klienta, żadne połączenie z puli
 *     nie czeka na HTTP). Błąd → ponowienie z rosnącym odstępem; transakcja już
 *     zweryfikowana (P24 status 2) jest traktowana jak sukces.
 *  3. FAKT PŁATNOŚCI (krótka transakcja, blokada zamówienia): PENDING/EXPIRED → PAID. Ten
 *     zapis nigdy nie jest wycofywany razem z realizacją.
 *  4. REALIZACJA — [OrderFulfillmentService.fulfillIfPaid], własna transakcja, idempotentna
 *     po zamówieniu. Błąd zostawia PAID; ponawia `PaymentReconciliationJob`.
 *
 * Krok 1 bierze na notyfikację krótką dzierżawę (`next_attempt_at`), więc ta sama płatność nie
 * jest weryfikowana równolegle przez webhook, ponowienie od P24 i worker. Błędy przejściowe nie
 * mają stanu końcowego — ponowienie co najwyżej co godzinę, z alarmem po 12 próbach.
 *
 * Nie wolno wołać z wnętrza otwartej transakcji: kroki muszą zatwierdzać się osobno.
 */
@Service
class PaymentNotificationProcessor(
    private val notificationRepository: PaymentNotificationRepository,
    private val orderRepository: PaymentOrderRepository,
    private val p24Client: Przelewy24Client,
    private val fulfillmentService: OrderFulfillmentService,
    private val accessPolicy: SubscriptionAccessPolicy,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)
    private val objectMapper = jacksonObjectMapper()

    private sealed interface Decision {
        data class Finished(val outcome: NotificationOutcome, val fulfillOrderId: UUID? = null) : Decision
        data class Verify(
            val orderId: UUID,
            val sessionId: String,
            val providerOrderId: Long,
            val amountCents: Long,
            val alreadyVerified: Boolean
        ) : Decision
    }

    /**
     * Zapisuje notyfikację dokładnie raz (unikat provider + orderId P24) i zwraca id wiersza.
     * Duplikat trafia w istniejący wiersz — bez wyjątku i bez drugiego wpisu.
     */
    fun record(
        notification: Przelewy24Client.P24Notification,
        source: PaymentNotificationSource = PaymentNotificationSource.WEBHOOK,
        alreadyVerified: Boolean = false
    ): UUID = tx.execute {
        notificationRepository.insertIfAbsent(
            id = UUID.randomUUID(),
            provider = PaymentNotificationEntity.PROVIDER_P24,
            providerOrderId = notification.orderId,
            sessionId = notification.sessionId,
            amountCents = notification.amount,
            currency = notification.currency,
            payload = objectMapper.writeValueAsString(notification),
            source = source.name,
            alreadyVerified = alreadyVerified,
            receivedAt = accessPolicy.now()
        )
        notificationRepository.findIdByProviderOrder(PaymentNotificationEntity.PROVIDER_P24, notification.orderId)
    }!!

    fun process(notificationId: UUID): NotificationOutcome {
        val decision = try {
            tx.execute { decide(notificationId) }!!
        } catch (e: Exception) {
            return scheduleRetry(notificationId, "Decyzja nie powiodła się: ${e.message}", e)
        }

        val verify = when (decision) {
            is Decision.Finished -> {
                decision.fulfillOrderId?.let { fulfill(it) }
                return count(decision.outcome)
            }
            is Decision.Verify -> decision
        }

        if (!verify.alreadyVerified) {
            try {
                p24Client.verifyTransaction(verify.sessionId, verify.providerOrderId, verify.amountCents)
            } catch (e: Exception) {
                // P24 nie dokumentuje odpowiedzi na ponowną weryfikację. Transakcja, którą P24
                // zgłasza jako zweryfikowaną (status 2) dla tego samego orderId, jest opłacona.
                val state = runCatching { p24Client.getTransactionBySessionId(verify.sessionId) }.getOrNull()
                if (state != null && state.orderId == verify.providerOrderId && state.status == P24_STATUS_RETURNED) {
                    // P24 zwróciło niezweryfikowaną transakcję płatnikowi — `verify` już nigdy się
                    // nie uda, a ponawianie co godzinę bez końca zjadałoby czas jedynego wątku jobów.
                    return closeForReview(
                        notificationId,
                        "P24 zwróciło transakcję ${verify.providerOrderId} bez weryfikacji (status 3) — płatnik dostał zwrot, do wyjaśnienia z klientem"
                    )
                }
                if (state?.status != P24_STATUS_VERIFIED || state.orderId != verify.providerOrderId) {
                    return scheduleRetry(notificationId, "Weryfikacja w P24 nie powiodła się: ${e.message}", e)
                }
                logger.info("P24 verify sessionId={} odrzucony, ale transakcja jest już zweryfikowana — kontynuuję", verify.sessionId)
            }
        }

        val paidOrderId = try {
            tx.execute { recordPayment(notificationId, verify) }
        } catch (e: Exception) {
            return scheduleRetry(notificationId, "Zapis płatności nie powiódł się: ${e.message}", e)
        } ?: return count(NotificationOutcome.NEEDS_REVIEW)

        return count(fulfill(paidOrderId))
    }

    // ─── Krok 1: decyzja ──────────────────────────────────────────────────────

    private fun decide(notificationId: UUID): Decision {
        val n = notificationRepository.lockById(notificationId)
            ?: return Decision.Finished(NotificationOutcome.DUPLICATE)
        when (n.status) {
            PaymentNotificationStatus.PROCESSED -> return Decision.Finished(NotificationOutcome.DUPLICATE, n.orderId)
            PaymentNotificationStatus.REJECTED -> return Decision.Finished(NotificationOutcome.REJECTED)
            PaymentNotificationStatus.NEEDS_REVIEW -> return Decision.Finished(NotificationOutcome.NEEDS_REVIEW)
            PaymentNotificationStatus.RECEIVED, PaymentNotificationStatus.UNMATCHED -> Unit
        }
        val now = accessPolicy.now()
        // Ponowienie zaplanowane na później albo obsługa w toku (dzierżawa niżej): ponowiona przez
        // P24 notyfikacja nie woła `verify` drugi raz i nie zużywa limitu prób, a worker nie
        // ściga się z webhookiem, który właśnie weryfikuje tę samą płatność.
        if (n.nextAttemptAt?.isAfter(now) == true) return Decision.Finished(NotificationOutcome.RETRY_SCHEDULED)

        val order = orderRepository.lockBySessionId(n.sessionId)
        if (order == null) {
            if (Duration.between(n.receivedAt, now) > UNMATCHED_GIVE_UP_AFTER) {
                n.markNeedsReview("Brak zamówienia o sesji ${n.sessionId} po ${UNMATCHED_GIVE_UP_AFTER.toHours()} h", now)
                logger.error("P24 notyfikacja {} bez zamówienia (sesja {}) — do wyjaśnienia ręcznie", n.id, n.sessionId)
                return Decision.Finished(NotificationOutcome.NEEDS_REVIEW)
            }
            n.markUnmatched(now.plus(UNMATCHED_RETRY_DELAY))
            logger.warn("P24 notyfikacja {} dla nieznanej sesji {} — ponowię dopasowanie", n.id, n.sessionId)
            return Decision.Finished(NotificationOutcome.UNMATCHED)
        }

        if (order.status.isPaid) {
            if (order.p24OrderId == n.providerOrderId) {
                n.markProcessed(order.id, order.studioId, now)
                // PAID bez realizacji (np. poprzednia próba nie dokończyła) — dokończ.
                return Decision.Finished(NotificationOutcome.DUPLICATE, fulfillOrderId = order.id)
            }
            n.markNeedsReview("Druga płatność (P24 orderId ${n.providerOrderId}) za opłacone zamówienie ${order.id}", now)
            meterRegistry.counter("payments.notifications.second.payment").increment()
            logger.error(
                "P24: druga płatność za zamówienie {} (sesja {}, opłacone przez {}, nowa {}) — do zwrotu ręcznie",
                order.id, order.sessionId, order.p24OrderId, n.providerOrderId
            )
            return Decision.Finished(NotificationOutcome.NEEDS_REVIEW)
        }

        if (n.amountCents != order.amountCents || n.currency != order.currency) {
            n.markRejected(
                "Kwota/waluta notyfikacji (${n.amountCents} ${n.currency}) niezgodna z zamówieniem (${order.amountCents} ${order.currency})",
                order.id, order.studioId, now
            )
            meterRegistry.counter("payments.notifications.rejected").increment()
            logger.error("P24 notyfikacja {} odrzucona: niezgodna kwota dla zamówienia {}", n.id, order.id)
            return Decision.Finished(NotificationOutcome.REJECTED)
        }

        if (!order.status.acceptsPayment) {
            n.markNeedsReview("Zamówienie ${order.id} w stanie ${order.status} nie przyjmuje płatności", now)
            return Decision.Finished(NotificationOutcome.NEEDS_REVIEW)
        }

        n.lease(now.plus(PROCESSING_LEASE))
        return Decision.Verify(order.id, n.sessionId, n.providerOrderId, n.amountCents, n.alreadyVerified)
    }

    // ─── Krok 3: fakt płatności ───────────────────────────────────────────────

    /** Zwraca id zamówienia do realizacji albo null, gdy notyfikacja trafiła do przeglądu. */
    private fun recordPayment(notificationId: UUID, verify: Decision.Verify): UUID? {
        val n = notificationRepository.lockById(notificationId)!!
        val order = orderRepository.lockById(verify.orderId)!!
        val now = accessPolicy.now()

        when {
            order.status.acceptsPayment -> {
                // Chwila zapłaty = przyjście notyfikacji, nie koniec naszej weryfikacji: ponowienia
                // (do godziny) nie mogą przesunąć płatności za koniec okresu, do którego ją wyceniono.
                order.markPaid(verify.providerOrderId, minOf(n.receivedAt, now))
                logger.info("P24 płatność przyjęta: zamówienie {} (sesja {}, orderId {})", order.id, order.sessionId, verify.providerOrderId)
            }
            order.status.isPaid && order.p24OrderId == verify.providerOrderId -> Unit // równoległa obsługa nas wyprzedziła
            else -> {
                n.markNeedsReview("Zamówienie ${order.id} zmieniło stan na ${order.status} w trakcie obsługi płatności", now)
                return null
            }
        }
        n.markProcessed(order.id, order.studioId, now)
        return order.id
    }

    // ─── Krok 4: realizacja ───────────────────────────────────────────────────

    private fun fulfill(orderId: UUID): NotificationOutcome = try {
        when (fulfillmentService.fulfillIfPaid(orderId)) {
            FulfillmentOutcome.FULFILLED, FulfillmentOutcome.ALREADY_SETTLED -> NotificationOutcome.FULFILLED
            FulfillmentOutcome.REFUND_REQUIRED -> NotificationOutcome.REFUND_REQUIRED
            FulfillmentOutcome.NOT_PAID -> NotificationOutcome.PAID_AWAITING_FULFILLMENT
            // Zamówienie opłacone przez P24 nigdy nie jest darmowe — gdyby jednak, człowiek ma to zobaczyć.
            FulfillmentOutcome.CANCELLED -> NotificationOutcome.NEEDS_REVIEW
        }
    } catch (e: Exception) {
        // Zamówienie zostaje PAID — fakt płatności jest zapisany; realizację ponowi worker.
        logger.error("Realizacja opłaconego zamówienia {} nie powiodła się — ponowi worker", orderId, e)
        NotificationOutcome.PAID_AWAITING_FULFILLMENT
    }

    private fun closeForReview(notificationId: UUID, reason: String): NotificationOutcome = tx.execute {
        val n = notificationRepository.lockById(notificationId) ?: return@execute NotificationOutcome.DUPLICATE
        if (!n.isOpen) return@execute when (n.status) {
            PaymentNotificationStatus.REJECTED -> NotificationOutcome.REJECTED
            PaymentNotificationStatus.NEEDS_REVIEW -> NotificationOutcome.NEEDS_REVIEW
            else -> NotificationOutcome.DUPLICATE
        }
        n.markNeedsReview(reason, accessPolicy.now())
        logger.error("P24 notyfikacja {}: {}", n.id, reason)
        NotificationOutcome.NEEDS_REVIEW
    }!!.let(::count)

    private fun scheduleRetry(notificationId: UUID, error: String, cause: Exception): NotificationOutcome {
        logger.warn("P24 notyfikacja {}: {} — ponowienie", notificationId, error, cause)
        return try {
            tx.execute {
                val n = notificationRepository.lockById(notificationId) ?: return@execute NotificationOutcome.RETRY_SCHEDULED
                // Równoległa obsługa zdążyła ją rozstrzygnąć — jej wynik zostaje.
                if (!n.isOpen) return@execute when (n.status) {
                    PaymentNotificationStatus.REJECTED -> NotificationOutcome.REJECTED
                    PaymentNotificationStatus.NEEDS_REVIEW -> NotificationOutcome.NEEDS_REVIEW
                    else -> NotificationOutcome.DUPLICATE
                }
                val now = accessPolicy.now()
                // Bez stanu końcowego: płatność, którą P24 ma, a której nie umiemy potwierdzić
                // (awaria P24, zła konfiguracja na kilka godzin), ma się sama domknąć, gdy przyczyna
                // minie. Dawniej po 12 próbach trafiała do przeglądu i nic już jej nie ruszało —
                // ani ponowienia P24, ani rekoncyliacja. Teraz: co godzinę i alarm.
                if (n.attempts + 1 >= LONG_RETRY_AFTER_ATTEMPTS) {
                    meterRegistry.counter("payments.notifications.retry.long").increment()
                    logger.error("P24 notyfikacja {} nadal nierozstrzygnięta po {} próbach: {} — ponawiam co godzinę", n.id, n.attempts + 1, error)
                }
                n.scheduleRetry(error, now.plus(backoff(n.attempts)))
                NotificationOutcome.RETRY_SCHEDULED
            }!!.let(::count)
        } catch (e: Exception) {
            // Nawet zapis ponowienia się nie udał (baza leży) — wiersz zostaje RECEIVED,
            // a worker weźmie go w kolejnym przebiegu.
            logger.error("P24 notyfikacja {}: nie udało się zaplanować ponowienia", notificationId, e)
            count(NotificationOutcome.RETRY_SCHEDULED)
        }
    }

    private fun count(outcome: NotificationOutcome): NotificationOutcome {
        meterRegistry.counter("payments.notifications.processed", "outcome", outcome.name).increment()
        return outcome
    }

    companion object {
        /** Stan transakcji w P24: wpłacona i zweryfikowana. */
        const val P24_STATUS_VERIFIED = 2
        /** Stan transakcji w P24: wpłacona, jeszcze niezweryfikowana. */
        const val P24_STATUS_PAID = 1
        /** Stan transakcji w P24: zwrócona płatnikowi (np. niezweryfikowana w terminie) — `verify` niemożliwy. */
        const val P24_STATUS_RETURNED = 3

        /** Od tylu prób każde kolejne ponowienie jest alarmem (backoff dochodzi już do godziny). */
        private const val LONG_RETRY_AFTER_ATTEMPTS = 12
        /** Ile trwa dzierżawa obsługi (decyzja → verify → zapis); po niej podejmie ją worker. */
        private val PROCESSING_LEASE: Duration = Duration.ofMinutes(2)
        private val UNMATCHED_RETRY_DELAY: Duration = Duration.ofMinutes(5)
        private val UNMATCHED_GIVE_UP_AFTER: Duration = Duration.ofHours(24)

        /** 1, 2, 4 … minut, nie więcej niż godzina. */
        private fun backoff(attempts: Int): Duration = Duration.ofMinutes((1L shl attempts.coerceIn(0, 6)).coerceAtMost(60))
    }
}
