package pl.detailing.crm.employee.payroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.PayrollEntry
import pl.detailing.crm.employee.infrastructure.PayrollEntryRepository
import pl.detailing.crm.shared.*
import java.time.YearMonth

@Service
class ListPayrollHandler(
    private val payrollRepository: PayrollEntryRepository
) {
    suspend fun handleForEmployee(employeeId: EmployeeId, studioId: StudioId): List<PayrollEntry> =
        withContext(Dispatchers.IO) {
            payrollRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }
        }

    suspend fun handleForPeriod(studioId: StudioId, period: YearMonth): List<PayrollEntry> =
        withContext(Dispatchers.IO) {
            payrollRepository.findByStudioIdAndPeriod(studioId.value, period.toString())
                .map { it.toDomain() }
        }
}
