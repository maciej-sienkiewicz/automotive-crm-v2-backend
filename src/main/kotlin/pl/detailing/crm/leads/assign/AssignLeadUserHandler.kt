package pl.detailing.crm.leads.assign

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.UserRole
import java.time.Instant
import java.util.UUID

private val TERMINAL_STATUSES = setOf(LeadStatus.LOST, LeadStatus.NO_SHOW, LeadStatus.COMPLETED, LeadStatus.CONFIRMED)

data class AssignLeadUserCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val requestingUserId: UserId,
    val requestingUserName: String?,
    val requestingUserRole: UserRole,
    val assignedUserId: UUID?,
    val assignedUserName: String?
)

@Service
class AssignLeadUserHandler(
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(AssignLeadUserHandler::class.java)

    @Transactional
    suspend fun handle(command: AssignLeadUserCommand) = withContext(Dispatchers.IO) {
        val entity = leadRepository.findById(command.leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

        if (entity.studioId != command.studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        // DETAILER cannot unassign from a terminal-status lead (would corrupt stats)
        if (command.assignedUserId == null
            && command.requestingUserRole == UserRole.DETAILER
            && entity.status in TERMINAL_STATUSES
        ) {
            throw ForbiddenException("Nie możesz odpiąć pracownika od zamkniętego leada. Skontaktuj się z managerem.")
        }

        val oldAssignedUserId = entity.assignedUserId?.toString()
        val oldAssignedUserName = entity.assignedUserName
        entity.assignedUserId = command.assignedUserId
        entity.assignedUserName = command.assignedUserName
        entity.updatedAt = Instant.now()

        leadRepository.save(entity)

        log.info("[LEADS] Assigned user: leadId={}, assignedUserId={}", entity.id, command.assignedUserId)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.requestingUserId,
            userDisplayName = command.requestingUserName ?: "",
            module = AuditModule.LEAD,
            entityId = command.leadId.value.toString(),
            entityDisplayName = entity.customerName,
            action = AuditAction.LEAD_USER_ASSIGNED,
            changes = listOf(
                FieldChange("assignedUserId", oldAssignedUserId, command.assignedUserId?.toString()),
                FieldChange("assignedUserName", oldAssignedUserName, command.assignedUserName)
            )
        ))
    }
}
