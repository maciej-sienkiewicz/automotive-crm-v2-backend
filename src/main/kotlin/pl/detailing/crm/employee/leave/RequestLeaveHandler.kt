package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.LeaveRequest
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.LeaveBalanceRepository
import pl.detailing.crm.employee.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

@Service
class RequestLeaveHandler(
    private val employeeRepository: EmployeeRepository,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val leaveBalanceRepository: LeaveBalanceRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: RequestLeaveCommand): LeaveRequestId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Employee '${command.employeeId}' not found")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Cannot submit leave request for a terminated employee")
        }

        if (!command.endDate.isAfter(command.startDate) && command.endDate != command.startDate) {
            throw ValidationException("End date must be on or after start date")
        }

        // Check for overlapping approved/pending requests
        val overlapping = leaveRequestRepository.findOverlapping(
            command.employeeId.value, command.studioId.value, command.startDate, command.endDate
        )
        if (overlapping.isNotEmpty()) {
            throw ConflictException("Employee already has an overlapping leave request for this period")
        }

        val businessDays = countBusinessDays(command.startDate, command.endDate)

        // Update leave balance pending days for ANNUAL leave type
        if (command.leaveType == LeaveType.ANNUAL) {
            val year = command.startDate.year
            val balance = leaveBalanceRepository.findByEmployeeIdAndYear(
                command.employeeId.value, command.studioId.value, year
            )
            if (balance != null) {
                balance.pendingDays += businessDays
                leaveBalanceRepository.save(balance)
            }
        }

        val leaveRequest = LeaveRequest(
            id = LeaveRequestId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            leaveType = command.leaveType,
            startDate = command.startDate,
            endDate = command.endDate,
            businessDaysCount = businessDays,
            status = LeaveStatus.PENDING,
            reason = command.reason,
            reviewedBy = null,
            reviewedAt = null,
            reviewNote = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        leaveRequestRepository.save(LeaveRequestEntity.fromDomain(leaveRequest))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.LEAVE_REQUESTED,
            changes = listOf(
                FieldChange("leaveType", null, command.leaveType.name),
                FieldChange("startDate", null, command.startDate.toString()),
                FieldChange("endDate", null, command.endDate.toString()),
                FieldChange("businessDays", null, businessDays.toString())
            )
        ))

        leaveRequest.id
    }

    private fun countBusinessDays(start: LocalDate, end: LocalDate): Int {
        var count = 0
        var current = start
        while (!current.isAfter(end)) {
            if (current.dayOfWeek != DayOfWeek.SATURDAY && current.dayOfWeek != DayOfWeek.SUNDAY) {
                count++
            }
            current = current.plusDays(1)
        }
        return count
    }
}
