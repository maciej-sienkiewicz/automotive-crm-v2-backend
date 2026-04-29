package pl.detailing.crm.protocol.infrastructure

import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.ProtocolTemplateId

/**
 * A resolved protocol item ready to be instantiated as a VisitProtocol.
 *
 * Two kinds:
 *  - VisitDocumentProtocol: auto-filled from a ProtocolTemplate (PDF form with CRM data)
 *  - ConsentProtocol: uses a ConsentTemplate PDF as-is (no auto-fill, shown once per customer)
 *
 * Exactly one of templateId / consentTemplateId is non-null.
 */
data class ResolvedProtocol(
    val templateId: ProtocolTemplateId?,
    val consentTemplateId: ConsentTemplateId?,
    val consentDefinitionId: ConsentDefinitionId?,
    val isMandatory: Boolean,
    val displayOrder: Int
) {
    val isConsentProtocol: Boolean get() = consentTemplateId != null

    companion object {
        fun fromVisitDocument(
            templateId: ProtocolTemplateId,
            isMandatory: Boolean,
            displayOrder: Int,
            consentDefinitionId: ConsentDefinitionId? = null
        ) = ResolvedProtocol(
            templateId = templateId,
            consentTemplateId = null,
            consentDefinitionId = consentDefinitionId,
            isMandatory = isMandatory,
            displayOrder = displayOrder
        )

        fun fromConsent(
            consentDefinitionId: ConsentDefinitionId,
            consentTemplateId: ConsentTemplateId,
            isMandatory: Boolean,
            displayOrder: Int
        ) = ResolvedProtocol(
            templateId = null,
            consentTemplateId = consentTemplateId,
            consentDefinitionId = consentDefinitionId,
            isMandatory = isMandatory,
            displayOrder = displayOrder
        )
    }
}
