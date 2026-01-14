package pl.detailing.crm.customer.consent.definition

import pl.detailing.crm.shared.ConsentDefinitionId

/**
 * Result of creating a consent definition.
 */
data class CreateConsentDefinitionResult(
    val definitionId: ConsentDefinitionId,
    val slug: String,
    val name: String
)
