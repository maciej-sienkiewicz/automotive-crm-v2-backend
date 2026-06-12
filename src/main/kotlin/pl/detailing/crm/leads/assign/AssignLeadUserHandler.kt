package pl.detailing.crm.leads.assign

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
import java.time.Instant

data class AssignLeadUserCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val requestingUserId: UserId?,
    val requestingUserName: String?,
    val assignedUserId: String?,
    val assignedUserName: String?
)

data class AssignLeadUserResult(
    val leadId: LeadId,
    val assignedUserId: String?,
    val assignedUserName: String?
)

@Service
class AssignLeadUserHandler(
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: AssignLeadUserCommand): AssignLeadUserResult =
        withContext(Dispatchers.IO) {
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            val oldUserId = entity.assignedUserId
            entity.assignedUserId = command.assignedUserId
            entity.assignedUserName = command.assignedUserName
            entity.updatedAt = Instant.now()
            leadRepository.save(entity)

            log.info("[LEADS] User assignment changed: leadId={}, assignedUserId={}", entity.id, entity.assignedUserId)

            val changes = auditService.computeChanges(
                mapOf("assignedUserId" to oldUserId),
                mapOf("assignedUserId" to command.assignedUserId)
            )
            if (changes.isNotEmpty()) {
                auditService.log(LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.requestingUserId ?: UserId(java.util.UUID.randomUUID()),
                    userDisplayName = command.requestingUserName ?: "",
                    module = AuditModule.LEAD,
                    entityId = command.leadId.value.toString(),
                    entityDisplayName = entity.customerName,
                    action = AuditAction.UPDATE,
                    changes = changes
                ))
            }

            AssignLeadUserResult(
                leadId = command.leadId,
                assignedUserId = entity.assignedUserId,
                assignedUserName = entity.assignedUserName
            )
        }
}
