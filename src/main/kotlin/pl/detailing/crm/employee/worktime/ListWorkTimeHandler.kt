package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.WorkTimeEntry
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*
import java.time.LocalDate

@Service
class ListWorkTimeHandler(
    private val workTimeRepository: WorkTimeEntryRepository
) {
    suspend fun handleForEmployee(
        employeeId: EmployeeId,
        studioId: StudioId,
        from: LocalDate? = null,
        to: LocalDate? = null
    ): List<WorkTimeEntry> = withContext(Dispatchers.IO) {
        if (from != null && to != null) {
            workTimeRepository.findByEmployeeIdAndDateRange(employeeId.value, studioId.value, from, to)
                .map { it.toDomain() }
        } else {
            workTimeRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }
        }
    }

    suspend fun handlePendingForStudio(studioId: StudioId): List<WorkTimeEntry> = withContext(Dispatchers.IO) {
        workTimeRepository.findPendingByStudioId(studioId.value).map { it.toDomain() }
    }
}
