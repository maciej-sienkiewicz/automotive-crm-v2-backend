package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.shared.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class LeaveCalendarEmployee(
    val id: UUID,
    val fullName: String
)

data class LeaveCalendarDay(
    val date: LocalDate,
    val count: Int,
    val employees: List<LeaveCalendarEmployee>
)

@Service
class GetLeaveCalendarHandler(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository
) {
    suspend fun handle(studioId: StudioId, from: LocalDate, to: LocalDate): List<LeaveCalendarDay> =
        withContext(Dispatchers.IO) {
            if (to.isBefore(from)) {
                throw ValidationException("Data 'to' nie może być wcześniejsza niż data 'from'")
            }
            if (ChronoUnit.DAYS.between(from, to) > 100) {
                throw ValidationException("Zakres dat nie może przekraczać 100 dni")
            }

            val leaves = employeeLeaveRepository.findOverlappingRange(studioId.value, from, to)
            if (leaves.isEmpty()) return@withContext emptyList()

            val employeeNames = employeeRepository.findByStudioId(studioId.value)
                .associate { it.id to "${it.firstName} ${it.lastName}" }

            // Dzień po dniu: zbiór pracowników, których urlop obejmuje dany dzień
            val days = mutableListOf<LeaveCalendarDay>()
            var day = from
            while (!day.isAfter(to)) {
                val current = day
                val employeesOnLeave = leaves
                    .filter { !current.isBefore(it.startDate) && !current.isAfter(it.endDate) }
                    .map { it.employeeId }
                    .distinct()
                if (employeesOnLeave.isNotEmpty()) {
                    days.add(LeaveCalendarDay(
                        date = current,
                        count = employeesOnLeave.size,
                        employees = employeesOnLeave.map {
                            LeaveCalendarEmployee(id = it, fullName = employeeNames[it] ?: "")
                        }
                    ))
                }
                day = day.plusDays(1)
            }
            days
        }
}
