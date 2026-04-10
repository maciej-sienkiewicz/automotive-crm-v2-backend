package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.LeaveBalanceRepository
import pl.detailing.crm.employee.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class ReviewLeaveHandler(
    private val leaveRequestRepository: LeaveRequestRepository,
    private val leaveBalanceRepository: LeaveBalanceRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: ReviewLeaveCommand) = withContext(Dispatchers.IO) {
        val entity = leaveRequestRepository.findByIdAndStudioId(command.leaveRequestId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Leave request '${command.leaveRequestId}' not found")

        if (entity.status != LeaveStatus.PENDING) {
            throw ValidationException("Leave request is not in PENDING status")
        }

        val oldStatus = entity.status
        val newStatus = if (command.approve) LeaveStatus.APPROVED else LeaveStatus.REJECTED

        entity.status = newStatus
        entity.reviewedBy = command.userId.value
        entity.reviewedAt = Instant.now()
        entity.reviewNote = command.reviewNote
        entity.updatedAt = Instant.now()
        leaveRequestRepository.save(entity)

        // Update leave balance
        if (entity.leaveType == LeaveType.VACATION) {
            val year = entity.startDate.year
            val balance = leaveBalanceRepository.findByEmployeeIdAndYear(
                entity.employeeId, command.studioId.value, year
            )
            if (balance != null) {
                balance.pendingDays = maxOf(0, balance.pendingDays - entity.businessDaysCount)
                if (command.approve) {
                    balance.usedDays += entity.businessDaysCount
                }
                leaveBalanceRepository.save(balance)
            }
        }

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = entity.employeeId.toString(),
            action = if (command.approve) AuditAction.LEAVE_APPROVED else AuditAction.LEAVE_REJECTED,
            changes = listOf(
                FieldChange("status", oldStatus.name, newStatus.name),
                FieldChange("leaveRequestId", null, command.leaveRequestId.toString()),
                FieldChange("reviewNote", null, command.reviewNote)
            )
        ))
    }
}
