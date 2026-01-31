package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class UpdateCustomerHandler(
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(command: UpdateCustomerCommand): UpdateCustomerResult =
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Check if email is unique (if changed)
            if (entity.email != command.email) {
                val existingWithEmail = customerRepository.findActiveByStudioIdAndEmail(
                    studioId = command.studioId.value,
                    email = command.email
                )
                if (existingWithEmail != null && existingWithEmail.id != entity.id) {
                    throw IllegalArgumentException("Email already in use by another customer")
                }
            }

            // Check if phone is unique (if changed)
            if (entity.phone != command.phone) {
                val existingWithPhone = customerRepository.findActiveByStudioIdAndPhone(
                    studioId = command.studioId.value,
                    phone = command.phone
                )
                if (existingWithPhone != null && existingWithPhone.id != entity.id) {
                    throw IllegalArgumentException("Phone already in use by another customer")
                }
            }

            // Update basic fields
            entity.firstName = command.firstName
            entity.lastName = command.lastName
            entity.email = command.email
            entity.phone = command.phone

            // Update home address
            if (command.homeAddress != null) {
                entity.homeAddressStreet = command.homeAddress.street
                entity.homeAddressCity = command.homeAddress.city
                entity.homeAddressPostalCode = command.homeAddress.postalCode
                entity.homeAddressCountry = command.homeAddress.country
            } else {
                entity.homeAddressStreet = null
                entity.homeAddressCity = null
                entity.homeAddressPostalCode = null
                entity.homeAddressCountry = null
            }

            // Update audit fields
            entity.updatedBy = command.userId.value
            entity.updatedAt = Instant.now()

            // Save
            val saved = customerRepository.save(entity)

            UpdateCustomerResult(
                id = saved.id.toString(),
                firstName = saved.firstName,
                lastName = saved.lastName,
                email = saved.email,
                phone = saved.phone,
                homeAddress = command.homeAddress,
                updatedAt = saved.updatedAt
            )
        }
}
