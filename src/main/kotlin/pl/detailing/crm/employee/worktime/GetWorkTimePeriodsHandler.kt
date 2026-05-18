package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.YearMonth

data class WorkTimePeriodSummary(
    val period: String,
    val totalHours: BigDecimal,
    val status: TimesheetStatus
)

@Service
class GetWorkTimePeriodsHandler(
    private val employeeRepository: EmployeeRepository,
    private val workTimeRepository: WorkTimeEntryRepository
) {
    suspend fun handle(employeeId: EmployeeId, studioId: StudioId): List<WorkTimePeriodSummary> =
        withContext(Dispatchers.IO) {
            employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
                ?: throw EntityNotFoundException("Pracownik '$employeeId' nie został znaleziony")

            val entries = workTimeRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }

            entries
                .groupBy { YearMonth.from(it.date).toString() }
                .map { (period, periodEntries) ->
                    val totalHours = periodEntries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }
                    val status = when {
                        periodEntries.isNotEmpty() &&
                            periodEntries.all { it.status == WorkTimeStatus.APPROVED } ->
                            TimesheetStatus.APPROVED
                        else -> TimesheetStatus.DRAFT
                    }
                    WorkTimePeriodSummary(period = period, totalHours = totalHours, status = status)
                }
                .sortedByDescending { it.period }
        }
}
