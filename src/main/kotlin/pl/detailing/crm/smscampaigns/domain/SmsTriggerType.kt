package pl.detailing.crm.smscampaigns.domain

/**
 * Identifies which automation rule triggered an SMS send.
 * Used for deduplication: one SMS per (appointmentId, triggerType).
 */
enum class SmsTriggerType {
    PRE_VISIT,
    POST_VISIT
}
