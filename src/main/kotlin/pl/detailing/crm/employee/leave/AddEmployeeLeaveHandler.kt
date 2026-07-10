package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.domain.EmployeeLeave
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class AddEmployeeLeaveCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val leaveType: LeaveType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val note: String?
)

data class AddEmployeeLeaveResult(val leaveId: UUID)

@Service
class AddEmployeeLeaveHandler(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: AddEmployeeLeaveCommand): AddEmployeeLeaveResult = withContext(Dispatchers.IO) {
        if (command.endDate.isBefore(command.startDate)) {
            throw ValidationException("Data zakończenia urlopu nie może być wcześniejsza niż data rozpoczęcia")
        }

        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw NotFoundException("Pracownik nie istnieje")

        val leave = EmployeeLeave(
            id = UUID.randomUUID(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            leaveType = command.leaveType,
            startDate = command.startDate,
            endDate = command.endDate,
            note = command.note?.trim()?.takeIf { it.isNotEmpty() },
            createdBy = command.userId,
            createdAt = Instant.now()
        )

        employeeLeaveRepository.save(EmployeeLeaveEntity.fromDomain(leave))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.UPDATE,
            changes = listOf(
                FieldChange("leave", null, "${leave.leaveType} ${leave.startDate} – ${leave.endDate}")
            )
        ))

        AddEmployeeLeaveResult(leaveId = leave.id)
    }
}
