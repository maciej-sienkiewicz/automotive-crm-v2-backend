package pl.detailing.crm.customer.consent.getstatus

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentStatus
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerConsentId
import pl.detailing.crm.shared.ProtocolStage
import java.time.Instant

data class GetConsentStatusResult(
    val consents: List<ConsentStatusItem>
)

data class ConsentStatusItem(
    val definitionId: ConsentDefinitionId,
    val definitionSlug: String,
    val definitionName: String,
    val isDefinitionActive: Boolean,
    val stage: ProtocolStage?,              // null when definition is inactive
    val isMandatory: Boolean,
    val displayOrder: Int,
    val status: ConsentStatus,
    val currentTemplateId: ConsentTemplateId?,
    val currentVersion: Int?,
    val signedTemplateId: ConsentTemplateId?,
    val signedVersion: Int?,
    val signedAt: Instant?,
    val downloadUrl: String?,
    val consentId: CustomerConsentId?
)
