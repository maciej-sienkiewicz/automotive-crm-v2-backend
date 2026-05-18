package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*
import java.time.Instant

data class ApproveWorkTimeCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val entryId: WorkTimeEntryId,
    val approve: Boolean,
    val rejectionReason: String?
)

@Service
class ApproveWorkTimeHandler(
    private val workTimeRepository: WorkTimeEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: ApproveWorkTimeCommand) = withContext(Dispatchers.IO) {
        val entity = workTimeRepository.findByIdAndStudioId(command.entryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Wpis czasu pracy '${command.entryId}' nie został znaleziony")

        if (entity.status != WorkTimeStatus.PENDING) {
            throw ValidationException("Wpis czasu pracy nie ma statusu PENDING")
        }

        val newStatus = if (command.approve) WorkTimeStatus.APPROVED else WorkTimeStatus.REJECTED

        entity.status = newStatus
        entity.approvedBy = command.userId.value
        entity.approvedAt = Instant.now()
        entity.updatedAt = Instant.now()
        if (!command.approve && command.rejectionReason != null) {
            entity.notes = (entity.notes?.let { "$it\nRejection: " } ?: "Rejection: ") + command.rejectionReason
        }

        workTimeRepository.save(entity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = entity.employeeId.toString(),
            action = if (command.approve) AuditAction.WORK_TIME_APPROVED else AuditAction.WORK_TIME_REJECTED,
            changes = listOf(
                FieldChange("status", "PENDING", newStatus.name),
                FieldChange("entryId", null, command.entryId.toString()),
                FieldChange("date", null, entity.date.toString())
            )
        ))
    }
}
