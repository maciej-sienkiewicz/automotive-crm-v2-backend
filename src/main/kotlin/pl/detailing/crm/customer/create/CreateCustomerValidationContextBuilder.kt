package pl.detailing.crm.customer.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.infrastructure.CustomerRepository

@Component
class CreateCustomerValidationContextBuilder(
    private val customerRepository: CustomerRepository
) {
    suspend fun build(command: CreateCustomerCommand): CreateCustomerValidationContext =
        withContext(Dispatchers.IO) {
            val emailExistsDeferred = async {
                customerRepository.existsActiveByStudioIdAndEmail(
                    command.studioId.value,
                    command.email.trim().lowercase()
                )
            }

            val phoneExistsDeferred = async {
                customerRepository.existsActiveByStudioIdAndPhone(
                    command.studioId.value,
                    command.phone.trim()
                )
            }

            CreateCustomerValidationContext(
                studioId = command.studioId,
                firstName = command.firstName,
                lastName = command.lastName,
                email = command.email,
                phone = command.phone,
                companyData = command.companyData,
                emailExists = emailExistsDeferred.await(),
                phoneExists = phoneExistsDeferred.await()
            )
        }
}
