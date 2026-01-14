package pl.detailing.crm.customer.consent.sign

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository

/**
 * Builds validation context for signing a consent.
 * Fetches all necessary data in parallel.
 */
@Component
class SignConsentValidationContextBuilder(
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerRepository: CustomerRepository
) {

    suspend fun build(command: SignConsentCommand): SignConsentValidationContext =
        withContext(Dispatchers.IO) {
            // Parallel async queries
            val templateDeferred = async {
                consentTemplateRepository.findByIdAndStudioId(
                    command.templateId.value,
                    command.studioId.value
                )?.toDomain()
            }

            val customerExistsDeferred = async {
                customerRepository.findByIdAndStudioId(
                    command.customerId.value,
                    command.studioId.value
                ) != null
            }

            SignConsentValidationContext(
                studioId = command.studioId,
                customerId = command.customerId,
                templateId = command.templateId,
                template = templateDeferred.await(),
                customerExists = customerExistsDeferred.await()
            )
        }
}
