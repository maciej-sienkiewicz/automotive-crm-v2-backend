package pl.detailing.crm.customer.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.domain.RecordOrigin
import pl.detailing.crm.shared.CustomerId
import java.time.Instant

@Service
class CreateCustomerHandler(
    private val validatorComposite: CreateCustomerValidatorComposite,
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService,
    private val businessEventPublisher: BusinessEventPublisher
) {

    @Transactional
    suspend fun handle(command: CreateCustomerCommand): CreateCustomerResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val customer = Customer(
            id = CustomerId.random(),
            studioId = command.studioId,
            firstName = command.firstName?.trim(),
            lastName = command.lastName?.trim(),
            email = command.email?.trim()?.lowercase()?.ifBlank { null },
            phone = command.phone?.trim()?.ifBlank { null },
            homeAddress = command.homeAddress,
            companyData = command.companyData,
            isActive = true,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val entity = CustomerEntity.fromDomain(customer)
        customerRepository.save(entity)

        // Live metrics — klient założony świadomie w kartotece (a nie mimochodem przy rezerwacji).
        businessEventPublisher.publish(
            tenantId = command.studioId,
            type = BusinessEventType.CUSTOMER_CREATED,
            dimensionValue = RecordOrigin.DIRECT.name,
            attributes = mapOf(
                "customerId" to customer.id.value.toString(),
                "userId" to command.userId.value.toString()
            )
        )

        // Live metrics — klient od razu z NIP-em; to samo przejście brak → wartość,
        // co przy edycji danych firmy (patrz UpdateCompanyHandler).
        if (!command.companyData?.nip.isNullOrBlank()) {
            businessEventPublisher.publish(
                tenantId = command.studioId,
                type = BusinessEventType.CUSTOMER_NIP_SET,
                attributes = mapOf("customerId" to customer.id.value.toString())
            )
        }

        val displayName = listOfNotNull(customer.firstName, customer.lastName).joinToString(" ").ifBlank { customer.email ?: customer.phone ?: "" }

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.CUSTOMER,
            entityId = customer.id.value.toString(),
            entityDisplayName = displayName,
            action = AuditAction.CREATE,
            changes = listOfNotNull(
                customer.firstName?.let { pl.detailing.crm.audit.domain.FieldChange("firstName", null, it) },
                customer.lastName?.let { pl.detailing.crm.audit.domain.FieldChange("lastName", null, it) },
                customer.email?.let { pl.detailing.crm.audit.domain.FieldChange("email", null, it) },
                customer.phone?.let { pl.detailing.crm.audit.domain.FieldChange("phone", null, it) }
            )
        ))

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
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?
)
