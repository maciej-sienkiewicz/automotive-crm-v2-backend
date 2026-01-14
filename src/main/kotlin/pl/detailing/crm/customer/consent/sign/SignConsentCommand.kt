package pl.detailing.crm.customer.consent.sign

import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * Command to record a customer's acceptance of a consent template.
 *
 * @param studioId The studio/tenant ID
 * @param customerId The customer who is signing the consent
 * @param templateId The specific template version being signed
 * @param witnessedBy The employee/user who witnessed/recorded the signature
 */
data class SignConsentCommand(
    val studioId: StudioId,
    val customerId: CustomerId,
    val templateId: ConsentTemplateId,
    val witnessedBy: UserId
)
