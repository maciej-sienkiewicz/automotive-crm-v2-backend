package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class UpdateCompanyHandler(
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {
    suspend fun handle(command: UpdateCompanyCommand): UpdateCompanyResult =
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Capture old values for audit
            val oldValues = mapOf(
                "companyName" to entity.companyName,
                "companyNip" to entity.companyNip,
                "companyRegon" to entity.companyRegon,
                "companyAddressStreet" to entity.companyAddressStreet,
                "companyAddressCity" to entity.companyAddressCity,
                "companyAddressPostalCode" to entity.companyAddressPostalCode,
                "companyAddressCountry" to entity.companyAddressCountry
            )

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

            // Compute changes for audit
            val newValues = mapOf(
                "companyName" to saved.companyName,
                "companyNip" to saved.companyNip,
                "companyRegon" to saved.companyRegon,
                "companyAddressStreet" to saved.companyAddressStreet,
                "companyAddressCity" to saved.companyAddressCity,
                "companyAddressPostalCode" to saved.companyAddressPostalCode,
                "companyAddressCountry" to saved.companyAddressCountry
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
                    action = AuditAction.COMPANY_UPDATED,
                    changes = changes
                ))
            }

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
