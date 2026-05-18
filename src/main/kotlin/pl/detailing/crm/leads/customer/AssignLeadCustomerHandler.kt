package pl.detailing.crm.leads.customer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
import java.time.Instant

data class AssignLeadCustomerCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val userId: UserId?,
    val userName: String?,
    val customerId: CustomerId?
)

data class AssignLeadCustomerResult(
    val leadId: LeadId,
    val customerId: CustomerId?,
    val customerSnapshot: CustomerSnapshot?
)

data class CustomerSnapshot(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?
)

@Service
class AssignLeadCustomerHandler(
    private val leadRepository: LeadRepository,
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: AssignLeadCustomerCommand): AssignLeadCustomerResult =
        withContext(Dispatchers.IO) {
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            val oldCustomerId = entity.customerId?.toString()
            var snapshot: CustomerSnapshot? = null

            if (command.customerId != null) {
                val customer = customerRepository.findByIdAndStudioId(
                    id = command.customerId.value,
                    studioId = command.studioId.value
                ) ?: throw EntityNotFoundException("Klient nie został znaleziony: ${command.customerId}")

                entity.customerId = customer.id
                snapshot = CustomerSnapshot(
                    id = customer.id.toString(),
                    firstName = customer.firstName,
                    lastName = customer.lastName,
                    email = customer.email,
                    phone = customer.phone
                )
            } else {
                entity.customerId = null
            }

            entity.updatedAt = Instant.now()
            leadRepository.save(entity)

            log.info("[LEADS] Customer assignment changed: leadId={}, customerId={}", entity.id, entity.customerId)

            val newCustomerId = entity.customerId?.toString()
            val changes = auditService.computeChanges(
                mapOf("customerId" to oldCustomerId),
                mapOf("customerId" to newCustomerId)
            )
            if (changes.isNotEmpty()) {
                auditService.log(LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId ?: UserId(java.util.UUID.randomUUID()),
                    userDisplayName = command.userName ?: "",
                    module = AuditModule.LEAD,
                    entityId = command.leadId.value.toString(),
                    entityDisplayName = entity.customerName,
                    action = AuditAction.UPDATE,
                    changes = changes
                ))
            }

            AssignLeadCustomerResult(
                leadId = command.leadId,
                customerId = command.customerId,
                customerSnapshot = snapshot
            )
        }
}
