package pl.detailing.crm.customer.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

@Service
class DeleteCompanyHandler(
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {
    suspend fun handle(command: DeleteCompanyCommand) {
        withContext(Dispatchers.IO) {
            // Find the customer
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            val deletedCompanyName = entity.companyName
            val displayName = listOfNotNull(entity.firstName, entity.lastName).joinToString(" ").ifBlank { entity.email ?: entity.phone ?: "" }

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

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.CUSTOMER,
                entityId = command.customerId.value.toString(),
                entityDisplayName = displayName,
                action = AuditAction.COMPANY_DELETED,
                changes = listOfNotNull(
                    deletedCompanyName?.let { FieldChange("companyName", it, null) }
                )
            ))
        }
    }
}
