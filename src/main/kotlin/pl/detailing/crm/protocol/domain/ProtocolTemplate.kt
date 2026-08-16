package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * File format of an uploaded protocol template.
 *
 * - PDF: fillable AcroForm PDF — full pipeline support (auto-fill, tablet signing, sealing)
 * - HTML: self-contained HTML with data-field placeholders — filled server-side by
 *   placeholder substitution; the tablet signing flow requires PDF, so HTML templates
 *   are view/print oriented
 */
enum class ProtocolTemplateFormat(val fileExtension: String, val contentType: String) {
    PDF("pdf", "application/pdf"),
    HTML("html", "text/html")
}

/**
 * Result state of the required-fields verification of an uploaded template file.
 */
enum class ProtocolTemplateVerificationStatus {
    /** File not verified yet (freshly created, upload may still be in flight). */
    PENDING,

    /** File contains every required field — safe to use in the visit pipeline. */
    VERIFIED,

    /** File is missing required fields or is not parseable; must be re-uploaded. */
    REJECTED
}

/**
 * Protocol template represents a fillable form template (AcroForm PDF or HTML
 * with data-field placeholders).
 *
 * Templates are stored in S3 and contain field mappings that connect
 * form fields to CRM data.
 */
data class ProtocolTemplate(
    val id: ProtocolTemplateId,
    val studioId: StudioId,
    val name: String,
    val description: String?,
    val s3Key: String,                  // S3 object key for the template file
    val fileFormat: ProtocolTemplateFormat,
    val isDefault: Boolean,             // seeded system template, restored when a studio loses its check-in template
    val verificationStatus: ProtocolTemplateVerificationStatus,
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
