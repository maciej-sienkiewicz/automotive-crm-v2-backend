package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.YearMonth

data class ApprovePeriodResult(
    val approvedCount: Int,
    val skippedCount: Int
)

@Service
class ApprovePeriodWorkTimeHandler(
    private val employeeRepository: EmployeeRepository,
    private val workTimeRepository: WorkTimeEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(
        employeeId: EmployeeId,
        period: YearMonth,
        studioId: StudioId,
        userId: UserId,
        userName: String?
    ): ApprovePeriodResult = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik '$employeeId' nie został znaleziony")

        val from = period.atDay(1)
        val to = period.atEndOfMonth()

        val pendingEntries = workTimeRepository.findByEmployeeIdAndDateRangeAndStatus(
            employeeId.value, studioId.value, from, to, WorkTimeStatus.PENDING
        )

        if (pendingEntries.isEmpty()) {
            return@withContext ApprovePeriodResult(approvedCount = 0, skippedCount = 0)
        }

        val now = Instant.now()
        pendingEntries.forEach { entry ->
            entry.status = WorkTimeStatus.APPROVED
            entry.approvedBy = userId.value
            entry.approvedAt = now
            entry.updatedAt = now
        }
        workTimeRepository.saveAll(pendingEntries)

        val allPeriodEntries = workTimeRepository.findByEmployeeIdAndDateRange(
            employeeId.value, studioId.value, from, to
        )
        val skippedCount = allPeriodEntries.size - pendingEntries.size

        auditService.log(
            LogAuditCommand(
                studioId = studioId,
                userId = userId,
                userDisplayName = userName ?: "",
                module = AuditModule.EMPLOYEE,
                entityId = employeeId.value.toString(),
                entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
                action = AuditAction.WORK_TIME_APPROVED,
                changes = listOf(
                    FieldChange("period", null, period.toString()),
                    FieldChange("approvedCount", null, pendingEntries.size.toString()),
                    FieldChange("skippedCount", null, skippedCount.toString())
                )
            )
        )

        ApprovePeriodResult(approvedCount = pendingEntries.size, skippedCount = skippedCount)
    }
}
