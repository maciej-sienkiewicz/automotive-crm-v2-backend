package pl.detailing.crm.employee.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.EmploymentContract
import pl.detailing.crm.employee.infrastructure.EmploymentContractRepository
import pl.detailing.crm.shared.*

@Service
class ListContractsHandler(
    private val contractRepository: EmploymentContractRepository
) {
    suspend fun handle(employeeId: EmployeeId, studioId: StudioId): List<EmploymentContract> =
        withContext(Dispatchers.IO) {
            contractRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }
        }
}
