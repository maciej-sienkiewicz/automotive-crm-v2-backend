package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class UpdateCompanyHandler(
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(command: UpdateCompanyCommand): UpdateCompanyResult =
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Update company fields
            entity.companyName = command.name
            entity.companyNip = command.nip
            entity.companyRegon = command.regon
            entity.companyAddressStreet = command.address.street
            entity.companyAddressCity = command.address.city
            entity.companyAddressPostalCode = command.address.postalCode
            entity.companyAddressCountry = command.address.country

            // Update audit fields
            entity.updatedBy = command.userId.value
            entity.updatedAt = Instant.now()

            // Save
            val saved = customerRepository.save(entity)

            UpdateCompanyResult(
                id = saved.id.toString(),
                name = saved.companyName!!,
                nip = saved.companyNip!!,
                regon = saved.companyRegon!!,
                address = CompanyAddress(
                    street = saved.companyAddressStreet!!,
                    city = saved.companyAddressCity!!,
                    postalCode = saved.companyAddressPostalCode!!,
                    country = saved.companyAddressCountry!!
                )
            )
        }
}
