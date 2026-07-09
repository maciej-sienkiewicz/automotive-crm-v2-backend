package pl.detailing.crm.subscription.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Instant
import java.util.UUID

enum class SubscriptionEventType(val displayName: String) {
    SUBSCRIPTION_PURCHASE("Zakup subskrypcji"),
    SUBSCRIPTION_RENEWAL("Przedłużenie subskrypcji"),
    PLAN_UPGRADE("Zmiana planu (upgrade)"),
    PLAN_DOWNGRADE("Zmiana planu (downgrade)"),
    ADD_ON_ACTIVATION("Aktywacja modułu"),
    ADD_ON_DEACTIVATION("Dezaktywacja modułu")
}

/**
 * Immutable audit log of all billing-relevant subscription events.
 *
 * Written by:
 *   - OrderFulfillmentService (payments)           → SUBSCRIPTION_PURCHASE | SUBSCRIPTION_RENEWAL
 *                                                    | PLAN_UPGRADE | ADD_ON_ACTIVATION
 *   - PlanManagementService.schedulePlanDowngrade  → PLAN_DOWNGRADE
 *   - PlanManagementService.deactivateAddOnWithLog → ADD_ON_DEACTIVATION
 *
 * [amountInCents] is 0 for events with no charge (downgrades, deactivations).
 * [planKey]  is always set — records the plan active at the time of the event.
 * [addOnKey] is set only for add-on events.
 */
@Entity
@Table(
    name = "subscription_payment_log",
    indexes = [
        Index(name = "idx_payment_log_studio_created", columnList = "studio_id, created_at DESC")
    ]
)
class SubscriptionPaymentLogEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    val eventType: SubscriptionEventType,

    @Column(name = "amount_in_cents", nullable = false)
    val amountInCents: Long = 0,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String = "PLN",

    @Column(name = "transaction_id", length = 255)
    val transactionId: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_key", length = 50)
    val planKey: PlanKey? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "add_on_key", length = 50)
    val addOnKey: AddOnKey? = null,

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    val description: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
