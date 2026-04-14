package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*

@Service
class DeleteWorkTimeEntryHandler(
    private val employeeRepository: EmployeeRepository,
    private val workTimeRepository: WorkTimeEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(
        employeeId: EmployeeId,
        entryId: WorkTimeEntryId,
        studioId: StudioId,
        userId: UserId,
        userName: String?
    ) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Employee '$employeeId' not found")

        val entry = workTimeRepository.findByIdAndStudioId(entryId.value, studioId.value)
            ?: throw EntityNotFoundException("Work time entry '$entryId' not found")

        if (entry.employeeId != employeeId.value) {
            throw EntityNotFoundException("Work time entry '$entryId' not found")
        }

        if (entry.status != WorkTimeStatus.PENDING) {
            throw ForbiddenException(
                "Cannot delete work time entry '${entryId}' with status '${entry.status}' — only PENDING entries can be deleted"
            )
        }

        workTimeRepository.delete(entry)

        auditService.log(
            LogAuditCommand(
                studioId = studioId,
                userId = userId,
                userDisplayName = userName ?: "",
                module = AuditModule.EMPLOYEE,
                entityId = employeeId.value.toString(),
                entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
                action = AuditAction.WORK_TIME_ENTRY_DELETED,
                changes = listOf(
                    FieldChange("entryId", null, entryId.toString()),
                    FieldChange("date", null, entry.date.toString()),
                    FieldChange("entryType", null, entry.entryType.name),
                    FieldChange("effectiveHours", null, entry.effectiveHours.toPlainString())
                )
            )
        )
    }
}
