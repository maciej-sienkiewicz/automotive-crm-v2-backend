package pl.detailing.crm.customer.consent.getstatus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository

@Component
class ConsentStatusValidationContextBuilder(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {

    suspend fun build(command: GetConsentStatusCommand): ConsentStatusValidationContext =
        withContext(Dispatchers.IO) {
            val allDefinitionsDeferred = async {
                consentDefinitionRepository.findAllByStudioId(command.studioId.value)
                    .map { it.toDomain() }
            }

            val allTemplatesDeferred = async {
                consentTemplateRepository.findAllByStudioId(command.studioId.value)
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
                allDefinitions = allDefinitionsDeferred.await(),
                allTemplates = allTemplatesDeferred.await(),
                customerConsents = customerConsentsDeferred.await()
            )
        }
}
