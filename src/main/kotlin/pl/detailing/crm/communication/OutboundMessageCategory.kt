package pl.detailing.crm.communication

import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey

/**
 * Business category of an outbound message, mapped to the capability that must
 * be entitled for the send to happen.
 *
 * Transactional messages (reservation confirmations, reminders, visit-card links,
 * signing links, pickup notifications) belong to the CLIENT_COMMUNICATION module;
 * marketing campaigns are a separately sold module. The two must not unlock each
 * other, so every send path declares its category explicitly.
 */
enum class OutboundMessageCategory(val requiredCapability: CapabilityKey) {
    TRANSACTIONAL(CapabilityKey.COMM_SEND_TRANSACTIONAL),
    CAMPAIGN(CapabilityKey.COMM_SEND_CAMPAIGN),

    /**
     * Internal message to studio STAFF (signature-onboarding link). Belongs to the
     * e-signatures module that triggers it — an e-signatures-only studio must be
     * able to onboard employee signatures, and business rule "no customer
     * messaging without the communication module" concerns customers, not staff.
     */
    SIGNATURE_ONBOARDING(CapabilityKey.SIGNATURE_LOCAL)
}
