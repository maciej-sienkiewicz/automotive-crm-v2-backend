package pl.detailing.crm.visit.schedule

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class UpdateEstimatedCompletionDateHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: UpdateEstimatedCompletionDateCommand) {
        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val activeStatuses = setOf(VisitStatus.DRAFT, VisitStatus.IN_PROGRESS, VisitStatus.READY_FOR_PICKUP)
        if (visitEntity.status !in activeStatuses) {
            throw ValidationException("Estimated completion date can only be updated for active visits (DRAFT, IN_PROGRESS, READY_FOR_PICKUP)")
        }

        val oldValue = visitEntity.estimatedCompletionDate
        visitEntity.estimatedCompletionDate = command.estimatedCompletionDate
        visitEntity.updatedBy = command.userId.value
        visitEntity.updatedAt = Instant.now()
        visitRepository.save(visitEntity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName,
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = AuditAction.UPDATE,
            changes = listOf(FieldChange("estimatedCompletionDate", oldValue?.toString(), command.estimatedCompletionDate?.toString())),
            metadata = emptyMap()
        ))
    }
}

data class UpdateEstimatedCompletionDateCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val estimatedCompletionDate: Instant?
)
