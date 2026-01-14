package pl.detailing.crm.customer.consent.sign

import pl.detailing.crm.shared.CustomerConsentId
import java.time.Instant

/**
 * Result of signing a consent.
 */
data class SignConsentResult(
    val consentId: CustomerConsentId,
    val signedAt: Instant
)
