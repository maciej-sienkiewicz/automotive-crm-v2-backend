package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class DeleteCompanyHandler(
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(command: DeleteCompanyCommand) {
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Clear all company fields
            entity.companyName = null
            entity.companyNip = null
            entity.companyRegon = null
            entity.companyAddressStreet = null
            entity.companyAddressCity = null
            entity.companyAddressPostalCode = null
            entity.companyAddressCountry = null

            // Update audit fields
            entity.updatedBy = command.userId.value
            entity.updatedAt = Instant.now()

            // Save
            customerRepository.save(entity)
        }
    }
}
