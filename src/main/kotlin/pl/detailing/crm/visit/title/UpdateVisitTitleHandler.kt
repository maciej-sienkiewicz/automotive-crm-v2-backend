package pl.detailing.crm.visit.title

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class UpdateVisitTitleHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: UpdateVisitTitleCommand) {
        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val oldTitle = visitEntity.title
        visitEntity.title = command.title
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
            changes = listOf(FieldChange("title", oldTitle, command.title)),
            metadata = emptyMap()
        ))
    }
}

data class UpdateVisitTitleCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val title: String?
)
