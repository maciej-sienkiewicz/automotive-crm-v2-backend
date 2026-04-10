package pl.detailing.crm.employee.compensation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.CompensationConfig
import pl.detailing.crm.employee.infrastructure.CompensationConfigRepository
import pl.detailing.crm.shared.*

@Service
class GetCompensationHandler(
    private val compensationConfigRepository: CompensationConfigRepository
) {
    suspend fun handleCurrent(employeeId: EmployeeId, studioId: StudioId): CompensationConfig? =
        withContext(Dispatchers.IO) {
            compensationConfigRepository.findCurrentByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                ?.toDomain()
        }

    suspend fun handleHistory(employeeId: EmployeeId, studioId: StudioId): List<CompensationConfig> =
        withContext(Dispatchers.IO) {
            compensationConfigRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }
        }
}
