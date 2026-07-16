package pl.detailing.crm.smscampaigns.consent

import java.util.UUID

/**
 * Published (within the same transaction) when a customer's inbound "TAK" reply
 * confirms a pending SMS consent request and the visit's pending service items
 * have been approved.
 *
 * Lets other modules react to the confirmation without SmsConsentService knowing
 * about them — e.g. the Visit Card upsell module flips its REQUESTED suggestions
 * to CONFIRMED.
 */
data class SmsConsentConfirmedEvent(
    val visitId: UUID,
    val studioId: UUID,
    /** IDs of the visit service items approved by this confirmation. */
    val approvedServiceItemIds: List<UUID>
)
