package pl.detailing.crm.customer.consent.sign

import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

/**
 * Validation context for signing a consent.
 * Contains pre-fetched data needed for validation.
 */
data class SignConsentValidationContext(
    val studioId: StudioId,
    val customerId: CustomerId,
    val templateId: ConsentTemplateId,
    val template: ConsentTemplate?,
    val customerExists: Boolean
)
