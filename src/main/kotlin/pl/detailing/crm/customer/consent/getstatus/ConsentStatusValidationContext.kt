package pl.detailing.crm.customer.consent.getstatus

import pl.detailing.crm.customer.consent.domain.ConsentDefinition
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

/**
 * Validation context for getting consent status.
 * Contains all data needed to determine the status of consents for a customer.
 */
data class ConsentStatusValidationContext(
    val studioId: StudioId,
    val customerId: CustomerId,
    val activeDefinitions: List<ConsentDefinition>,
    val activeTemplates: List<ConsentTemplate>,
    val customerConsents: List<CustomerConsent>
)
