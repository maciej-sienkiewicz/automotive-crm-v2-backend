package pl.detailing.crm.studio.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.studio.domain.Studio
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "studios",
    indexes = [
        Index(name = "idx_studios_created_at", columnList = "created_at"),
        Index(name = "idx_studios_subscription_status", columnList = "subscription_status"),
        Index(name = "idx_studios_trial_ends_at", columnList = "trial_ends_at")
    ]
)
class StudioEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 20)
    var subscriptionStatus: SubscriptionStatus,

    @Column(name = "trial_ends_at", columnDefinition = "timestamp with time zone")
    var trialEndsAt: Instant?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): Studio = Studio(
        id = StudioId(id),
        name = name,
        subscriptionStatus = subscriptionStatus,
        trialEndsAt = trialEndsAt,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(studio: Studio): StudioEntity = StudioEntity(
            id = studio.id.value,
            name = studio.name,
            subscriptionStatus = studio.subscriptionStatus,
            trialEndsAt = studio.trialEndsAt,
            createdAt = studio.createdAt
        )
    }
}
