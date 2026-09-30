package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.shared.*
import java.util.UUID

@Service
class DeleteEmployeeLeaveHandler(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        leaveId: UUID,
        requestedBy: UserId,
        requestedByName: String?
    ) = withContext(Dispatchers.IO) {
        val leaveEntity = employeeLeaveRepository.findByIdAndStudioId(leaveId, studioId.value)
            ?: throw NotFoundException("Urlop nie istnieje")

        if (leaveEntity.employeeId != employeeId.value) {
            throw NotFoundException("Urlop nie istnieje")
        }

        // Urlop z zatwierdzonego wniosku zdejmuje się odwołaniem wniosku, nie kasowaniem
        // wpisu: inaczej w kalendarzu urlopu by nie było, a podpisany wniosek dalej
        // mówiłby „zatwierdzony" i nikt by nie wiedział, kto i dlaczego go cofnął.
        if (leaveEntity.leaveRequestId != null) {
            throw ValidationException(
                "Ten urlop wynika z zatwierdzonego wniosku urlopowego. Odwołaj wniosek w zakładce " +
                    "„Wnioski urlopowe”, a wpis zniknie z kalendarza."
            )
        }

        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
        val employeeName = employeeEntity?.let { "${it.firstName} ${it.lastName}" } ?: ""

        employeeLeaveRepository.delete(leaveEntity)

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = employeeName,
            action = AuditAction.UPDATE,
            changes = listOf(
                FieldChange("leave", "${leaveEntity.leaveType} ${leaveEntity.startDate} – ${leaveEntity.endDate}", null)
            )
        ))
    }
}
