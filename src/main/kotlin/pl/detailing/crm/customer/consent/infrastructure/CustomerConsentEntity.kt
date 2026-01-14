package pl.detailing.crm.customer.consent.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.CustomerConsentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.*

/**
 * JPA entity for CustomerConsent.
 * Represents a customer's acceptance of a specific consent template version.
 *
 * This is an immutable, append-only record. Never update or delete these records.
 */
@Entity
@Table(
    name = "customer_consents",
    indexes = [
        Index(name = "idx_customer_consents_customer", columnList = "studio_id, customer_id"),
        Index(name = "idx_customer_consents_template", columnList = "template_id"),
        Index(name = "idx_customer_consents_signed_at", columnList = "signed_at")
    ]
)
class CustomerConsentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Column(name = "template_id", nullable = false, columnDefinition = "uuid")
    val templateId: UUID,

    @Column(name = "signed_at", nullable = false)
    val signedAt: Instant = Instant.now(),

    @Column(name = "witnessed_by", nullable = false, columnDefinition = "uuid")
    val witnessedBy: UUID
) {
    fun toDomain(): CustomerConsent = CustomerConsent(
        id = CustomerConsentId(id),
        studioId = StudioId(studioId),
        customerId = CustomerId(customerId),
        templateId = ConsentTemplateId(templateId),
        signedAt = signedAt,
        witnessedBy = UserId(witnessedBy)
    )

    companion object {
        fun fromDomain(consent: CustomerConsent): CustomerConsentEntity =
            CustomerConsentEntity(
                id = consent.id.value,
                studioId = consent.studioId.value,
                customerId = consent.customerId.value,
                templateId = consent.templateId.value,
                signedAt = consent.signedAt,
                witnessedBy = consent.witnessedBy.value
            )
    }
}
