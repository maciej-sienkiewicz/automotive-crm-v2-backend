package pl.detailing.crm.customer.consent.getstatus

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentStatus
import pl.detailing.crm.shared.ConsentTemplateId
import java.time.Instant

/**
 * Result of getting consent status for a customer.
 * Contains a list of all active consent definitions and their current status.
 */
data class GetConsentStatusResult(
    val consents: List<ConsentStatusItem>
)

/**
 * Status of a single consent definition for a customer.
 */
data class ConsentStatusItem(
    val definitionId: ConsentDefinitionId,
    val definitionSlug: String,
    val definitionName: String,
    val status: ConsentStatus,
    val currentTemplateId: ConsentTemplateId,
    val currentVersion: Int,
    val signedTemplateId: ConsentTemplateId?,
    val signedVersion: Int?,
    val signedAt: Instant?,
    val downloadUrl: String?  // Presigned URL for viewing the current template
)
