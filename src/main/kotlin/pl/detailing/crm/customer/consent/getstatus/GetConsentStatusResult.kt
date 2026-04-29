package pl.detailing.crm.customer.consent.getstatus

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentStatus
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerConsentId
import java.time.Instant

data class GetConsentStatusResult(
    val consents: List<ConsentStatusItem>
)

data class ConsentStatusItem(
    val definitionId: ConsentDefinitionId,
    val definitionSlug: String,
    val definitionName: String,
    val isDefinitionActive: Boolean,
    val status: ConsentStatus,
    val currentTemplateId: ConsentTemplateId?,  // null when definition is inactive
    val currentVersion: Int?,                    // null when definition is inactive
    val signedTemplateId: ConsentTemplateId?,
    val signedVersion: Int?,
    val signedAt: Instant?,
    val downloadUrl: String?,
    val consentId: CustomerConsentId?
)
