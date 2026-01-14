package pl.detailing.crm.customer.consent.domain

import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.CustomerConsentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * CustomerConsent represents a customer's acceptance of a specific consent template version.
 *
 * This is an immutable, append-only record. When a customer signs a new version,
 * a new CustomerConsent record is created. The old record is never updated or deleted.
 *
 * The signedAt timestamp records when the customer accepted the consent.
 * The witnessedBy field records which employee/user witnessed the signature.
 */
data class CustomerConsent(
    val id: CustomerConsentId,
    val studioId: StudioId,
    val customerId: CustomerId,
    val templateId: ConsentTemplateId,
    val signedAt: Instant,          // When the customer signed this consent
    val witnessedBy: UserId         // Which employee witnessed/recorded the signature
)
