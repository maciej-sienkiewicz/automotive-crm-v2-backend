package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class UpdateCustomerHandler(
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {
    suspend fun handle(command: UpdateCustomerCommand): UpdateCustomerResult =
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Capture old values for audit
            val oldValues = mapOf(
                "firstName" to entity.firstName,
                "lastName" to entity.lastName,
                "email" to entity.email,
                "phone" to entity.phone,
                "homeAddressStreet" to entity.homeAddressStreet,
                "homeAddressCity" to entity.homeAddressCity,
                "homeAddressPostalCode" to entity.homeAddressPostalCode,
                "homeAddressCountry" to entity.homeAddressCountry
            )

            val normalizedEmail = command.email?.trim()?.lowercase()?.ifBlank { null }
            val normalizedPhone = command.phone?.trim()?.ifBlank { null }

            // Check if email is unique (if changed)
            if (normalizedEmail != null && entity.email != normalizedEmail) {
                val existingWithEmail = customerRepository.findActiveByStudioIdAndEmail(
                    studioId = command.studioId.value,
                    email = normalizedEmail
                )
                if (existingWithEmail != null && existingWithEmail.id != entity.id) {
                    throw IllegalArgumentException("Email already in use by another customer")
                }
            }

            // Check if phone is unique (if changed)
            if (normalizedPhone != null && entity.phone != normalizedPhone) {
                val existingWithPhone = customerRepository.findActiveByStudioIdAndPhone(
                    studioId = command.studioId.value,
                    phone = normalizedPhone
                )
                if (existingWithPhone != null && existingWithPhone.id != entity.id) {
                    throw IllegalArgumentException("Phone already in use by another customer")
                }
            }

            if (normalizedEmail == null && normalizedPhone == null) {
                throw IllegalArgumentException("Wymagany jest co najmniej numer telefonu lub adres email klienta.")
            }

            // Update basic fields
            entity.firstName = command.firstName?.trim()
            entity.lastName = command.lastName?.trim()
            entity.email = normalizedEmail
            entity.phone = normalizedPhone

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

            // Compute changes for audit
            val newValues = mapOf(
                "firstName" to saved.firstName,
                "lastName" to saved.lastName,
                "email" to saved.email,
                "phone" to saved.phone,
                "homeAddressStreet" to saved.homeAddressStreet,
                "homeAddressCity" to saved.homeAddressCity,
                "homeAddressPostalCode" to saved.homeAddressPostalCode,
                "homeAddressCountry" to saved.homeAddressCountry
            )

            val changes = auditService.computeChanges(oldValues, newValues)
            val displayName = listOfNotNull(saved.firstName, saved.lastName).joinToString(" ").ifBlank { saved.email ?: saved.phone ?: "" }

            if (changes.isNotEmpty()) {
                auditService.log(LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName ?: "",
                    module = AuditModule.CUSTOMER,
                    entityId = command.customerId.value.toString(),
                    entityDisplayName = displayName,
                    action = AuditAction.UPDATE,
                    changes = changes
                ))
            }

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
