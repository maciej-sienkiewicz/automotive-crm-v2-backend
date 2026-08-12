package pl.detailing.crm.visit.technicalnote

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class UpdateTechnicalNoteHandler(
    private val visitRepository: VisitRepository,
    private val historyRepository: VisitTechnicalNoteHistoryRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: UpdateTechnicalNoteCommand) {
        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val oldContent = visitEntity.technicalNotes
        val newContent = command.content?.takeIf { it.isNotBlank() }

        visitEntity.technicalNotes = newContent
        visitEntity.updatedBy = command.userId.value
        visitEntity.updatedAt = Instant.now()
        visitRepository.save(visitEntity)

        val historyAction = when {
            oldContent == null && newContent != null -> "CREATED"
            newContent == null -> "CLEARED"
            else -> "UPDATED"
        }

        historyRepository.save(
            VisitTechnicalNoteHistoryEntity(
                visitId = command.visitId.value,
                studioId = command.studioId.value,
                content = newContent,
                action = historyAction,
                changedById = command.userId.value,
                changedByName = command.userName,
                changedAt = Instant.now()
            )
        )

        val auditAction = when (historyAction) {
            "CREATED" -> AuditAction.NOTE_ADDED
            "CLEARED" -> AuditAction.NOTE_DELETED
            else -> AuditAction.NOTE_UPDATED
        }

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName,
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = auditAction,
            changes = listOf(FieldChange("technicalNotes", oldContent, newContent)),
            metadata = emptyMap()
        ))
    }
}

data class UpdateTechnicalNoteCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val content: String?
)
