package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * Protocol template represents a fillable PDF form (AcroForm) template.
 *
 * Templates are stored in S3 and contain field mappings that connect
 * PDF form fields to CRM data.
 */
data class ProtocolTemplate(
    val id: ProtocolTemplateId,
    val studioId: StudioId,
    val name: String,
    val description: String?,
    val s3Key: String,                  // S3 object key for the PDF template
    val isActive: Boolean,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        require(name.isNotBlank()) { "Protocol template name cannot be blank" }
        require(s3Key.isNotBlank()) { "S3 key cannot be blank" }
    }

    fun deactivate(updatedBy: UserId): ProtocolTemplate {
        return copy(
            isActive = false,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    fun activate(updatedBy: UserId): ProtocolTemplate {
        return copy(
            isActive = true,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }
}
