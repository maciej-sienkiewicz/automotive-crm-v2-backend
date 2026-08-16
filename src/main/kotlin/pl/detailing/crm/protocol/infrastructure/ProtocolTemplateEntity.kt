package pl.detailing.crm.protocol.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import pl.detailing.crm.protocol.domain.ProtocolTemplateVerificationStatus
import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.*

/**
 * JPA entity for ProtocolTemplate.
 * Represents a fillable template (AcroForm PDF or HTML with data-field
 * placeholders) for visit protocols.
 */
@Entity
@Table(
    name = "protocol_templates",
    indexes = [
        Index(name = "idx_protocol_templates_studio", columnList = "studio_id"),
        Index(name = "idx_protocol_templates_studio_active", columnList = "studio_id, is_active")
    ]
)
class ProtocolTemplateEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "s3_key", nullable = false, length = 500)
    var s3Key: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "file_format", nullable = false, length = 10)
    var fileFormat: ProtocolTemplateFormat = ProtocolTemplateFormat.PDF,

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    var verificationStatus: ProtocolTemplateVerificationStatus = ProtocolTemplateVerificationStatus.PENDING,

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
    fun toDomain(): ProtocolTemplate = ProtocolTemplate(
        id = ProtocolTemplateId(id),
        studioId = StudioId(studioId),
        name = name,
        description = description,
        s3Key = s3Key,
        fileFormat = fileFormat,
        isDefault = isDefault,
        verificationStatus = verificationStatus,
        isActive = isActive,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(template: ProtocolTemplate): ProtocolTemplateEntity =
            ProtocolTemplateEntity(
                id = template.id.value,
                studioId = template.studioId.value,
                name = template.name,
                description = template.description,
                s3Key = template.s3Key,
                fileFormat = template.fileFormat,
                isDefault = template.isDefault,
                verificationStatus = template.verificationStatus,
                isActive = template.isActive,
                createdBy = template.createdBy.value,
                updatedBy = template.updatedBy.value,
                createdAt = template.createdAt,
                updatedAt = template.updatedAt
            )
    }
}
