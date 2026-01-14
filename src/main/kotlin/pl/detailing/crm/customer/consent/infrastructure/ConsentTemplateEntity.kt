package pl.detailing.crm.customer.consent.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.*

/**
 * JPA entity for ConsentTemplate.
 * Represents a specific version of a consent document PDF.
 */
@Entity
@Table(
    name = "consent_templates",
    indexes = [
        Index(name = "idx_consent_templates_studio_def", columnList = "studio_id, definition_id"),
        Index(name = "idx_consent_templates_def_version", columnList = "definition_id, version", unique = true),
        Index(name = "idx_consent_templates_active", columnList = "definition_id, is_active")
    ]
)
class ConsentTemplateEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "definition_id", nullable = false, columnDefinition = "uuid")
    val definitionId: UUID,

    @Column(name = "version", nullable = false)
    val version: Int,

    @Column(name = "s3_key", nullable = false, length = 500)
    val s3Key: String,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = false,

    @Column(name = "requires_resign", nullable = false)
    val requiresResign: Boolean = false,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): ConsentTemplate = ConsentTemplate(
        id = ConsentTemplateId(id),
        studioId = StudioId(studioId),
        definitionId = ConsentDefinitionId(definitionId),
        version = version,
        s3Key = s3Key,
        isActive = isActive,
        requiresResign = requiresResign,
        createdBy = UserId(createdBy),
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(template: ConsentTemplate): ConsentTemplateEntity =
            ConsentTemplateEntity(
                id = template.id.value,
                studioId = template.studioId.value,
                definitionId = template.definitionId.value,
                version = template.version,
                s3Key = template.s3Key,
                isActive = template.isActive,
                requiresResign = template.requiresResign,
                createdBy = template.createdBy.value,
                createdAt = template.createdAt
            )
    }
}
