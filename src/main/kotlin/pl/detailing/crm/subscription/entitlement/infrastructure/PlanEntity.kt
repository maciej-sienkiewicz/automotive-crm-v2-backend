package pl.detailing.crm.subscription.entitlement.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.Plan
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.util.UUID

@Entity
@Table(name = "subscription_plans")
class PlanEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_key", nullable = false, unique = true, length = 50)
    val key: PlanKey,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "monthly_price_gross_cents", nullable = false)
    var monthlyPriceGrossCents: Long,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "subscription_plan_features",
        joinColumns = [JoinColumn(name = "plan_id")],
        inverseJoinColumns = [JoinColumn(name = "feature_id")]
    )
    val features: MutableSet<FeatureEntity> = mutableSetOf()
) {
    fun toDomain() = Plan(
        id = id,
        key = key,
        name = name,
        monthlyPriceGrossCents = monthlyPriceGrossCents,
        features = features.map { it.key }.toSet(),
        isActive = isActive,
        displayOrder = displayOrder
    )
}
