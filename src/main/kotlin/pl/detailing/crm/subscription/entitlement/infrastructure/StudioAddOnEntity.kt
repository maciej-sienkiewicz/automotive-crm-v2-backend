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
    val activatedAt: Instant = Instant.now()
)
