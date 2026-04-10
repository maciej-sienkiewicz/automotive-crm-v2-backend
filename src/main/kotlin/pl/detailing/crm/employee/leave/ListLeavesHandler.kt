package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.LeaveRequest
import pl.detailing.crm.employee.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.*

@Service
class ListLeavesHandler(
    private val leaveRequestRepository: LeaveRequestRepository
) {
    suspend fun handleForEmployee(employeeId: EmployeeId, studioId: StudioId): List<LeaveRequest> =
        withContext(Dispatchers.IO) {
            leaveRequestRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }
        }

    suspend fun handlePendingForStudio(studioId: StudioId): List<LeaveRequest> =
        withContext(Dispatchers.IO) {
            leaveRequestRepository.findPendingByStudioId(studioId.value).map { it.toDomain() }
        }
}
