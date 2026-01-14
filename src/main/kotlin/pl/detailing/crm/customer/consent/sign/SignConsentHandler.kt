package pl.detailing.crm.customer.consent.sign

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentEntity
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.shared.CustomerConsentId
import java.time.Instant

/**
 * Handler for signing a consent.
 *
 * This creates an immutable, append-only record of the customer's acceptance
 * of a specific consent template version.
 *
 * Note: Never update or delete CustomerConsent records. Always create new ones.
 */
@Service
class SignConsentHandler(
    private val validatorComposite: SignConsentValidatorComposite,
    private val customerConsentRepository: CustomerConsentRepository
) {

    @Transactional
    suspend fun handle(command: SignConsentCommand): SignConsentResult =
        withContext(Dispatchers.IO) {
            // Step 1: Validate
            validatorComposite.validate(command)

            // Step 2: Create new consent record
            val consent = CustomerConsent(
                id = CustomerConsentId.random(),
                studioId = command.studioId,
                customerId = command.customerId,
                templateId = command.templateId,
                signedAt = Instant.now(),
                witnessedBy = command.witnessedBy
            )

            // Step 3: Persist (append-only, never update)
            val entity = CustomerConsentEntity.fromDomain(consent)
            customerConsentRepository.save(entity)

            // Step 4: Return result
            SignConsentResult(
                consentId = consent.id,
                signedAt = consent.signedAt
            )
        }
}
