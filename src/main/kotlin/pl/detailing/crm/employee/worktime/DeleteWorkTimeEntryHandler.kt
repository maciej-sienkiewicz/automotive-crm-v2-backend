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
            ?: throw EntityNotFoundException("Pracownik '$employeeId' nie został znaleziony")

        val entry = workTimeRepository.findByIdAndStudioId(entryId.value, studioId.value)
            ?: throw EntityNotFoundException("Wpis czasu pracy '$entryId' nie został znaleziony")

        if (entry.employeeId != employeeId.value) {
            throw EntityNotFoundException("Wpis czasu pracy '$entryId' nie został znaleziony")
        }

        if (entry.status != WorkTimeStatus.PENDING) {
            throw ForbiddenException(
                "Nie można usunąć wpisu czasu pracy '${entryId}' ze statusem '${entry.status}' — można usuwać tylko wpisy ze statusem PENDING"
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
