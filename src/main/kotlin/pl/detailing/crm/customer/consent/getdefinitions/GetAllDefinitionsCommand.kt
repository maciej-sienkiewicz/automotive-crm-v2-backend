package pl.detailing.crm.customer.consent.getdefinitions

import pl.detailing.crm.shared.StudioId

/**
 * Command to get all consent definitions with their templates for a studio.
 */
data class GetAllDefinitionsCommand(
    val studioId: StudioId
)
