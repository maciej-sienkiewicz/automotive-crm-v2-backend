package pl.detailing.crm.customer.consent.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.customer.consent.domain.ConsentDefinition
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "consent_definitions",
    indexes = [
        Index(name = "idx_consent_defs_studio_active", columnList = "studio_id, is_active"),
        Index(name = "idx_consent_defs_studio_stage", columnList = "studio_id, stage, is_active")
    ]
)
class ConsentDefinitionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 20)
    var stage: ProtocolStage,

    @ElementCollection(fetch = FetchType.EAGER, targetClass = MarketingChannel::class)
    @CollectionTable(
        name = "consent_definition_marketing_channels",
        joinColumns = [JoinColumn(name = "definition_id")]
    )
    @Column(name = "channel")
    @Enumerated(EnumType.STRING)
    var marketingChannels: MutableSet<MarketingChannel> = mutableSetOf(),

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): ConsentDefinition = ConsentDefinition(
        id = ConsentDefinitionId(id),
        studioId = StudioId(studioId),
        name = name,
        description = description,
        stage = stage,
        marketingChannels = marketingChannels.toSet(),
        displayOrder = displayOrder,
        isActive = isActive,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(definition: ConsentDefinition): ConsentDefinitionEntity {
            val entity = ConsentDefinitionEntity(
                id = definition.id.value,
                studioId = definition.studioId.value,
                name = definition.name,
                description = definition.description,
                stage = definition.stage,
                displayOrder = definition.displayOrder,
                isActive = definition.isActive,
                createdBy = definition.createdBy.value,
                updatedBy = definition.updatedBy.value,
                createdAt = definition.createdAt,
                updatedAt = definition.updatedAt
            )
            entity.marketingChannels = definition.marketingChannels.toMutableSet()
            return entity
        }
    }
}
