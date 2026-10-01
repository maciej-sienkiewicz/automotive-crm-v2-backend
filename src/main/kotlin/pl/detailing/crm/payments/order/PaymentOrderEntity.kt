package pl.detailing.crm.payments.order

import jakarta.persistence.*
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Instant
import java.util.UUID

/**
 * What the buyer is paying for. The webhook fulfils the order based on this type.
 */
enum class PaymentOrderType(val displayName: String) {
    /** First purchase (studio has NO_PLAN, TRIALING or EXPIRED): plan + optional modules, 30 days of access. */
    INITIAL_PURCHASE("Aktywacja pakietu"),
    /** Extends the current subscription by 30 days at the price of the plan + modules of the NEXT period. */
    RENEWAL("Przedłużenie subskrypcji"),
    /** Mid-period upgrade BASIC → FULL, charged pro rata for the remaining days. */
    PLAN_UPGRADE("Zmiana pakietu"),
    /** Mid-period purchase of a single module, charged pro rata for the remaining days. */
    ADD_ON_PURCHASE("Dokupienie modułu")
}

/**
 * Stan zamówienia. „Zapłacone" i „zrealizowane" to dwa różne fakty i dwa różne stany:
 *
 * ```
 * PENDING ──płatność zweryfikowana──▶ PAID ──efekt zastosowany──▶ FULFILLED
 *    │                                  └──efekt niemożliwy──▶ REFUND_REQUIRED
 *    └──porzucone──▶ EXPIRED ──spóźniona płatność──▶ PAID
 * ```
 *
 * Wcześniej błąd realizacji cofał PAID do PENDING razem z resztą transakcji — fakt
 * otrzymania pieniędzy znikał z bazy (audyt, P6). Teraz PAID jest zapisywane osobno
 * i nigdy nie jest wycofywane; nieudana realizacja zostawia PAID, a ponawia ją worker.
 */
enum class PaymentOrderStatus {
    /** Zamówienie utworzone (i zwykle zarejestrowane w P24); płatności jeszcze nie ma. */
    PENDING,
    /** Pieniądze otrzymane i zweryfikowane w P24; efekt biznesowy jeszcze niezastosowany. */
    PAID,
    /** Efekt biznesowy zastosowany — stan końcowy udanego zakupu. */
    FULFILLED,
    /** Rejestracja w P24 nie powiodła się (zamówienie nigdy nie było do opłacenia) albo stan historyczny. */
    FAILED,
    /** Wycofane przed płatnością; stan historyczny. */
    CANCELLED,
    /**
     * Porzucone — nikt nie zapłacił w czasie ważności transakcji P24. NIE jest stanem
     * końcowym dla pieniędzy: spóźniona, zweryfikowana płatność przenosi zamówienie do PAID.
     */
    EXPIRED,
    /**
     * Pieniądze przyjęte, ale efektu nie da się zastosować (moduł już aktywny, plan już
     * zmieniony, studio opłacone innym zamówieniem). Wymaga zwrotu przez operatora —
     * świadomy stan zamiast płatności przepadającej po cichu (audyt, P2c, P7).
     */
    REFUND_REQUIRED;

    /** Płatność za zamówienie w tym stanie jest przyjmowana (a nie traktowana jako druga). */
    val acceptsPayment: Boolean get() = this == PENDING || this == EXPIRED || this == FAILED || this == CANCELLED

    /** Pieniądze za to zamówienie już są. */
    val isPaid: Boolean get() = this == PAID || this == FULFILLED || this == REFUND_REQUIRED
}

/**
 * A single payment intent sent to Przelewy24.
 *
 * [sessionId] is our unique P24 session identifier (also used to correlate webhook
 * notifications). One order = one P24 transaction; retries create new orders.
 *
 * Każda zmiana stanu przechodzi przez metody tej klasy i odbywa się pod blokadą wiersza
 * (`PaymentOrderRepository.lockById`) — dwie obsługi tej samej płatności nie mogą już obie
 * przeczytać PENDING i obie zrealizować zamówienia (audyt, P1).
 */
@Entity
@Table(
    name = "payment_orders",
    indexes = [
        Index(name = "idx_payment_orders_studio_created", columnList = "studio_id, created_at DESC"),
        Index(name = "idx_payment_orders_session", columnList = "session_id", unique = true)
    ]
)
class PaymentOrderEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Column(name = "session_id", nullable = false, unique = true, length = 100)
    val sessionId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, columnDefinition = "VARCHAR(40)")
    val type: PaymentOrderType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "VARCHAR(20)")
    var status: PaymentOrderStatus = PaymentOrderStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_key", columnDefinition = "VARCHAR(50)")
    val planKey: PlanKey? = null,

    /** Comma-separated [AddOnKey] names; empty when the order carries no modules. */
    @Column(name = "add_on_keys", nullable = false, length = 500)
    val addOnKeysRaw: String = "",

    @Column(name = "amount_cents", nullable = false)
    val amountCents: Long,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "PLN",

    @Column(name = "description", nullable = false, length = 255)
    val description: String,

    @Column(name = "p24_token", length = 255)
    var p24Token: String? = null,

    @Column(name = "p24_order_id")
    var p24OrderId: Long? = null,

    @Column(name = "failure_reason", length = 500)
    var failureReason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "paid_at")
    var paidAt: Instant? = null,

    @Column(name = "fulfilled_at", columnDefinition = "timestamp with time zone")
    var fulfilledAt: Instant? = null,

    /** Ostatnie sprawdzenie stanu transakcji w API P24 przez worker rekoncyliacji. */
    @Column(name = "last_reconciled_at", columnDefinition = "timestamp with time zone")
    var lastReconciledAt: Instant? = null,

    // Null przed pierwszym zapisem → persist zamiast merge; potem optymistyczna blokada (audyt, D2, D4).
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    var version: Long? = null
) {
    val addOnKeys: List<AddOnKey>
        get() = addOnKeysRaw.split(',').filter { it.isNotBlank() }.map { AddOnKey.valueOf(it) }

    /** Płatność zweryfikowana: zapisuje fakt otrzymania pieniędzy. Wołać pod blokadą wiersza. */
    fun markPaid(p24OrderId: Long?, at: Instant) {
        check(status.acceptsPayment) { "Zamówienie $id w stanie $status nie przyjmuje płatności" }
        status = PaymentOrderStatus.PAID
        if (p24OrderId != null) this.p24OrderId = p24OrderId
        paidAt = at
    }

    fun markFulfilled(at: Instant) {
        check(status == PaymentOrderStatus.PAID) { "Zamówienie $id w stanie $status nie może zostać zrealizowane" }
        status = PaymentOrderStatus.FULFILLED
        fulfilledAt = at
    }

    fun markRefundRequired(reason: String, at: Instant) {
        check(status == PaymentOrderStatus.PAID) { "Zamówienie $id w stanie $status nie może czekać na zwrot" }
        status = PaymentOrderStatus.REFUND_REQUIRED
        failureReason = reason.take(500)
        fulfilledAt = at
    }

    fun expire(reason: String) {
        if (status != PaymentOrderStatus.PENDING) return
        status = PaymentOrderStatus.EXPIRED
        failureReason = reason.take(500)
    }

    fun fail(reason: String) {
        if (status != PaymentOrderStatus.PENDING) return
        status = PaymentOrderStatus.FAILED
        failureReason = reason.take(500)
    }

    companion object {
        fun encodeAddOnKeys(keys: Collection<AddOnKey>): String = keys.sortedBy { it.name }.joinToString(",") { it.name }
    }
}
