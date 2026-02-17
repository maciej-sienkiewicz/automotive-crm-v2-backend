package pl.detailing.crm.visit.transitions.archive

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class ArchiveVisitHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
    // TODO: Add services for side-effects (ArchiveService, DataRetentionService, etc.)
) {

    @Transactional
    suspend fun handle(command: ArchiveVisitCommand): ArchiveVisitResult {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        // Force load lazy collections within transaction
        visitEntity.serviceItems.size  // Force load serviceItems
        visitEntity.photos.size  // Force load photos

        val visit = visitEntity.toDomain()

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.archive(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Audit logging
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visit.visitNumber}",
            action = AuditAction.VISIT_ARCHIVED,
            changes = listOf(FieldChange("status", visit.status.name, updatedVisit.status.name))
        ))

        // Step 5: Side-effects (to be implemented)
        // TODO: Move photos to archive storage if needed
        // TODO: Generate final reports if needed
        // TODO: Update analytics/reporting
        // Example:
        // archiveService.moveVisitToArchiveStorage(updatedVisit)
        // reportingService.updateArchivedVisitMetrics(updatedVisit)

        return ArchiveVisitResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status
        )
    }
}

/**
 * Command to archive visit
 */
data class ArchiveVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val userName: String? = null
)

/**
 * Result of archiving visit
 */
data class ArchiveVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus
)
