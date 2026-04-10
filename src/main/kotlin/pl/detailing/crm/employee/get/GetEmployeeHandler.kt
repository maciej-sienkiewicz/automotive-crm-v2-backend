package pl.detailing.crm.employee.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.Employee
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*

@Service
class GetEmployeeHandler(
    private val employeeRepository: EmployeeRepository
) {
    suspend fun handle(employeeId: EmployeeId, studioId: StudioId): Employee = withContext(Dispatchers.IO) {
        val entity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Employee '$employeeId' not found")
        entity.toDomain()
    }
}
