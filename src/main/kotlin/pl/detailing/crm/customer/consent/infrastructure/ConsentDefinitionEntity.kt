package pl.detailing.crm.customer.consent.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.customer.consent.domain.ConsentDefinition
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.*

@Entity
@Table(
    name = "consent_definitions",
    indexes = [
        Index(name = "idx_consent_defs_studio_slug", columnList = "studio_id, slug", unique = true),
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

    @Column(name = "slug", nullable = false, length = 100)
    var slug: String,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 20)
    var stage: ProtocolStage,

    @Column(name = "is_mandatory", nullable = false)
    var isMandatory: Boolean = false,

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
        slug = slug,
        name = name,
        description = description,
        stage = stage,
        isMandatory = isMandatory,
        displayOrder = displayOrder,
        isActive = isActive,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(definition: ConsentDefinition): ConsentDefinitionEntity =
            ConsentDefinitionEntity(
                id = definition.id.value,
                studioId = definition.studioId.value,
                slug = definition.slug,
                name = definition.name,
                description = definition.description,
                stage = definition.stage,
                isMandatory = definition.isMandatory,
                displayOrder = definition.displayOrder,
                isActive = definition.isActive,
                createdBy = definition.createdBy.value,
                updatedBy = definition.updatedBy.value,
                createdAt = definition.createdAt,
                updatedAt = definition.updatedAt
            )
    }
}
