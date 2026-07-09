package pl.detailing.crm.subscription.entitlement.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.subscription.entitlement.domain.AddOn
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import java.util.UUID

@Entity
@Table(name = "subscription_add_ons")
class AddOnEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "add_on_key", nullable = false, unique = true, length = 50)
    val key: AddOnKey,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    /**
     * Null means the add-on is not yet priced — it exists in the catalog
     * but cannot be purchased until a price is set.
     */
    @Column(name = "monthly_price_gross_cents")
    var monthlyPriceGrossCents: Long? = null,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    /**
     * False when the module is under development — visible in catalog but not purchasable.
     */
    @Column(name = "is_available", nullable = false)
    var isAvailable: Boolean = true,

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "subscription_add_on_features",
        joinColumns = [JoinColumn(name = "add_on_id")],
        inverseJoinColumns = [JoinColumn(name = "feature_id")]
    )
    val features: MutableSet<FeatureEntity> = mutableSetOf()
) {
    fun toDomain() = AddOn(
        id = id,
        key = key,
        name = name,
        description = description,
        monthlyPriceGrossCents = monthlyPriceGrossCents,
        features = features.map { it.key }.toSet(),
        isActive = isActive,
        isAvailable = isAvailable
    )
}
