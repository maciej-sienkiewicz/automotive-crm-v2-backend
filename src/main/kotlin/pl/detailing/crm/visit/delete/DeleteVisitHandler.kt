package pl.detailing.crm.visit.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Soft-deletes a visit by setting deletedAt timestamp, regardless of its status.
 * The visit and all its associated data remain in the database and can be viewed
 * by passing includeDeleted=true to the list endpoint.
 */
@Service
class DeleteVisitHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: DeleteVisitCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioId(
            command.visitId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

        val now = Instant.now()
        visitEntity.deletedAt = now
        visitEntity.updatedBy = command.userId.value
        visitEntity.updatedAt = now

        visitRepository.save(visitEntity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = AuditAction.VISIT_DELETED,
            changes = listOf(FieldChange("deletedAt", null, now.toString()))
        ))
    }
}

data class DeleteVisitCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)
