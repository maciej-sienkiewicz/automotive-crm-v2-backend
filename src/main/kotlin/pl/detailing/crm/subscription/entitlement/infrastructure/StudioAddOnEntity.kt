package pl.detailing.crm.subscription.entitlement.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "studio_subscription_add_ons",
    uniqueConstraints = [UniqueConstraint(
        name = "uq_studio_add_ons",
        columnNames = ["studio_subscription_plan_id", "add_on_id"]
    )]
)
class StudioAddOnEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "studio_subscription_plan_id", nullable = false)
    val studioSubscriptionPlan: StudioSubscriptionPlanEntity,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "add_on_id", nullable = false)
    val addOn: AddOnEntity,

    @Column(name = "activated_at", nullable = false)
    val activatedAt: Instant = Instant.now(),

    /**
     * Moduł wyłączony „z końcem okresu": działa do tej chwili, nie wchodzi do ceny odnowienia,
     * a usuwa go [pl.detailing.crm.subscription.management.PlanDowngradeScheduler]. Null —
     * moduł odnawia się razem z planem. Dawniej dezaktywacja zdejmowała moduł natychmiast,
     * choć był opłacony do końca okresu (audyt, S6).
     */
    @Column(name = "cancel_at", columnDefinition = "timestamp with time zone")
    var cancelAt: Instant? = null
)
