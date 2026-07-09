package pl.detailing.crm.subscription.entitlement.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.Feature
import java.util.UUID

@Entity
@Table(name = "subscription_features")
class FeatureEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Enumerated(EnumType.STRING)
    @Column(name = "feature_key", nullable = false, unique = true, length = 50)
    val key: FeatureKey,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true
) {
    fun toDomain() = Feature(id = id, key = key, isActive = isActive)
}
