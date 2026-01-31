package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class UpdateNotesHandler(
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(command: UpdateNotesCommand): UpdateNotesResult =
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Update notes
            entity.notes = command.notes

            // Update audit fields
            entity.updatedBy = command.userId.value
            entity.updatedAt = Instant.now()

            // Save
            val saved = customerRepository.save(entity)

            UpdateNotesResult(
                notes = saved.notes ?: "",
                updatedAt = saved.updatedAt
            )
        }
}
