package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.domain.EmployeeLeave
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.shared.*

@Service
class ListEmployeeLeavesHandler(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository
) {
    suspend fun handle(studioId: StudioId, employeeId: EmployeeId): List<EmployeeLeave> = withContext(Dispatchers.IO) {
        employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw NotFoundException("Pracownik nie istnieje")

        employeeLeaveRepository.findByStudioIdAndEmployeeId(studioId.value, employeeId.value)
            .map { it.toDomain() }
    }
}
