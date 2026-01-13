package pl.detailing.crm.customer.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CustomerId
import java.time.Instant

@Service
class CreateCustomerHandler(
    private val validatorComposite: CreateCustomerValidatorComposite,
    private val customerRepository: CustomerRepository
) {

    @Transactional
    suspend fun handle(command: CreateCustomerCommand): CreateCustomerResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val customer = Customer(
            id = CustomerId.random(),
            studioId = command.studioId,
            firstName = command.firstName.trim(),
            lastName = command.lastName.trim(),
            email = command.email.trim().lowercase(),
            phone = command.phone.trim(),
            homeAddress = command.homeAddress,
            companyData = command.companyData,
            notes = command.notes?.trim(),
            isActive = true,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val entity = CustomerEntity.fromDomain(customer)
        customerRepository.save(entity)

        CreateCustomerResult(
            customerId = customer.id,
            firstName = customer.firstName,
            lastName = customer.lastName,
            email = customer.email,
            phone = customer.phone
        )
    }
}

data class CreateCustomerResult(
    val customerId: CustomerId,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String
)
