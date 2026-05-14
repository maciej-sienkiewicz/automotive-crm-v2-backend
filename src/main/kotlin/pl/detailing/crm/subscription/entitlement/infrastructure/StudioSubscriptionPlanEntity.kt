package pl.detailing.crm.subscription.entitlement.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Maps a studio to its currently active plan.
 * One row per studio (UNIQUE on studio_id).
 *
 * Billing lifecycle (active/expired) is still tracked on StudioEntity
 * via subscriptionStatus / subscriptionEndsAt. This table only records
 * which feature-plan the studio is entitled to.
 */
@Entity
@Table(
    name = "studio_subscription_plans",
    uniqueConstraints = [UniqueConstraint(name = "uq_studio_subscription_plans_studio", columnNames = ["studio_id"])]
)
class StudioSubscriptionPlanEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    val plan: PlanEntity,

    @Column(name = "activated_at", nullable = false)
    val activatedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @OneToMany(
        mappedBy = "studioSubscriptionPlan",
        fetch = FetchType.EAGER,
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    val activeAddOns: MutableSet<StudioAddOnEntity> = mutableSetOf()
)
