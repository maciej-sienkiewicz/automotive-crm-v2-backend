package pl.detailing.crm.protocol.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.protocol.domain.ProtocolFieldMapping
import pl.detailing.crm.shared.CrmDataKey
import pl.detailing.crm.shared.ProtocolFieldMappingId
import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.*

/**
 * JPA entity for ProtocolFieldMapping.
 * Maps PDF form fields to CRM data sources.
 */
@Entity
@Table(
    name = "protocol_field_mappings",
    indexes = [
        Index(name = "idx_protocol_mappings_template", columnList = "studio_id, template_id"),
        Index(name = "idx_protocol_mappings_unique", columnList = "template_id, pdf_field_name", unique = true)
    ]
)
class ProtocolFieldMappingEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "template_id", nullable = false, columnDefinition = "uuid")
    val templateId: UUID,

    @Column(name = "pdf_field_name", nullable = false, length = 200)
    val pdfFieldName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "crm_data_key", nullable = false, length = 100)
    val crmDataKey: CrmDataKey,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): ProtocolFieldMapping = ProtocolFieldMapping(
        id = ProtocolFieldMappingId(id),
        studioId = StudioId(studioId),
        templateId = ProtocolTemplateId(templateId),
        pdfFieldName = pdfFieldName,
        crmDataKey = crmDataKey,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(mapping: ProtocolFieldMapping): ProtocolFieldMappingEntity =
            ProtocolFieldMappingEntity(
                id = mapping.id.value,
                studioId = mapping.studioId.value,
                templateId = mapping.templateId.value,
                pdfFieldName = mapping.pdfFieldName,
                crmDataKey = mapping.crmDataKey,
                createdAt = mapping.createdAt
            )
    }
}
