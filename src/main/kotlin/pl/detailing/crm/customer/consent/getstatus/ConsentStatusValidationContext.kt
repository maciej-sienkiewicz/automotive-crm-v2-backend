package pl.detailing.crm.customer.consent.getstatus

import pl.detailing.crm.customer.consent.domain.ConsentDefinition
import pl.detailing.crm.customer.consent.domain.ConsentTemplate
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class ConsentStatusValidationContext(
    val studioId: StudioId,
    val customerId: CustomerId,
    val allDefinitions: List<ConsentDefinition>,
    val allTemplates: List<ConsentTemplate>,
    val customerConsents: List<CustomerConsent>
)
