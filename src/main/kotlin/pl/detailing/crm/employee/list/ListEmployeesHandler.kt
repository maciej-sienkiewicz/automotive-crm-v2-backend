package pl.detailing.crm.employee.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.Employee
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*

@Service
class ListEmployeesHandler(
    private val employeeRepository: EmployeeRepository
) {
    suspend fun handle(studioId: StudioId, includeTerminated: Boolean = false): List<Employee> =
        withContext(Dispatchers.IO) {
            val entities = if (includeTerminated) {
                employeeRepository.findByStudioId(studioId.value)
            } else {
                employeeRepository.findActiveByStudioId(studioId.value)
            }
            entities.map { it.toDomain() }
        }
}
