package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

data class WorkTimeSummary(
    val employeeId: EmployeeId,
    val period: YearMonth,
    val totalHours: BigDecimal,
    val regularHours: BigDecimal,
    val overtimeHours: BigDecimal,
    val approvedHours: BigDecimal,
    val pendingHours: BigDecimal,
    val entriesCount: Int
)

@Service
class WorkTimeSummaryHandler(
    private val workTimeRepository: WorkTimeEntryRepository
) {
    suspend fun handle(employeeId: EmployeeId, studioId: StudioId, period: YearMonth): WorkTimeSummary =
        withContext(Dispatchers.IO) {
            val from = period.atDay(1)
            val to = period.atEndOfMonth()

            val entries = workTimeRepository.findByEmployeeIdAndDateRange(
                employeeId.value, studioId.value, from, to
            ).map { it.toDomain() }

            val approvedEntries = entries.filter { it.status == WorkTimeStatus.APPROVED }
            val pendingEntries = entries.filter { it.status == WorkTimeStatus.PENDING }

            val regularHours = approvedEntries
                .filter { it.entryType == WorkTimeEntryType.REGULAR }
                .fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }

            val overtimeHours = approvedEntries
                .filter { it.entryType != WorkTimeEntryType.REGULAR }
                .fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours }

            WorkTimeSummary(
                employeeId = employeeId,
                period = period,
                totalHours = regularHours + overtimeHours,
                regularHours = regularHours,
                overtimeHours = overtimeHours,
                approvedHours = approvedEntries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours },
                pendingHours = pendingEntries.fold(BigDecimal.ZERO) { acc, e -> acc + e.effectiveHours },
                entriesCount = entries.size
            )
        }
}
