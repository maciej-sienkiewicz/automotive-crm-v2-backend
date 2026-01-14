package pl.detailing.crm.customer.consent.domain

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * A consent template represents a specific version of a consent document.
 *
 * Each template is associated with a ConsentDefinition and has a version number.
 * Only one template per definition should have isActive = true at any time.
 *
 * The requiresResign flag determines whether customers who signed previous versions
 * must re-sign this new version.
 */
data class ConsentTemplate(
    val id: ConsentTemplateId,
    val studioId: StudioId,
    val definitionId: ConsentDefinitionId,
    val version: Int,               // Incremental version number (1, 2, 3, ...)
    val s3Key: String,              // S3 object key for the PDF file
    val isActive: Boolean,          // Only one active template per definition
    val requiresResign: Boolean,    // If true, customers must re-sign even if they signed older versions
    val createdBy: UserId,
    val createdAt: Instant
)
