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
    /** First purchase (studio has NO_PLAN or EXPIRED): plan + optional modules, 30 days of access. */
    INITIAL_PURCHASE("Aktywacja pakietu"),
    /** Extends the current subscription by 30 days at the current plan + modules price. */
    RENEWAL("Przedłużenie subskrypcji"),
    /** Mid-period upgrade BASIC → FULL, charged pro rata for the remaining days. */
    PLAN_UPGRADE("Zmiana pakietu"),
    /** Mid-period purchase of a single module, charged pro rata for the remaining days. */
    ADD_ON_PURCHASE("Dokupienie modułu")
}

enum class PaymentOrderStatus {
    /** Registered (or awaiting registration) at P24; payment not confirmed yet. */
    PENDING,
    /** Payment confirmed and the order's business effect applied. */
    PAID,
    /** P24 reported a problem or verification failed. */
    FAILED,
    /** Superseded/abandoned before payment. */
    CANCELLED
}

/**
 * A single payment intent sent to Przelewy24.
 *
 * [sessionId] is our unique P24 session identifier (also used to correlate webhook
 * notifications). One order = one P24 transaction; retries create new orders.
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
    @Column(name = "order_type", nullable = false, length = 40)
    val type: PaymentOrderType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PaymentOrderStatus = PaymentOrderStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_key", length = 50)
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
    var paidAt: Instant? = null
) {
    val addOnKeys: List<AddOnKey>
        get() = addOnKeysRaw.split(',').filter { it.isNotBlank() }.map { AddOnKey.valueOf(it) }

    companion object {
        fun encodeAddOnKeys(keys: Collection<AddOnKey>): String = keys.joinToString(",") { it.name }
    }
}
