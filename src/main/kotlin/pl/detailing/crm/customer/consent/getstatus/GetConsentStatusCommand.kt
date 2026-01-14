package pl.detailing.crm.customer.consent.getstatus

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

/**
 * Command to get the consent status for a specific customer.
 * Returns the status of all active consent definitions for the customer.
 */
data class GetConsentStatusCommand(
    val studioId: StudioId,
    val customerId: CustomerId
)
