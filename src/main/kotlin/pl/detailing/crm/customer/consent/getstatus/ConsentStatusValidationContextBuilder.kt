package pl.detailing.crm.customer.consent.getstatus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository

/**
 * Builds validation context for consent status checking.
 * Fetches all necessary data in parallel for optimal performance.
 */
@Component
class ConsentStatusValidationContextBuilder(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {

    suspend fun build(command: GetConsentStatusCommand): ConsentStatusValidationContext =
        withContext(Dispatchers.IO) {
            // Parallel async queries for all required data
            val activeDefinitionsDeferred = async {
                consentDefinitionRepository.findActiveByStudioId(command.studioId.value)
                    .map { it.toDomain() }
            }

            val activeTemplatesDeferred = async {
                consentTemplateRepository.findAllActiveByStudioId(command.studioId.value)
                    .map { it.toDomain() }
            }

            val customerConsentsDeferred = async {
                customerConsentRepository.findAllByCustomerIdAndStudioId(
                    command.customerId.value,
                    command.studioId.value
                ).map { it.toDomain() }
            }

            ConsentStatusValidationContext(
                studioId = command.studioId,
                customerId = command.customerId,
                activeDefinitions = activeDefinitionsDeferred.await(),
                activeTemplates = activeTemplatesDeferred.await(),
                customerConsents = customerConsentsDeferred.await()
            )
        }
}
