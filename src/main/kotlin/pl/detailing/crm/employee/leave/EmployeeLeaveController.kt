package pl.detailing.crm.employee.leave

import kotlinx.coroutines.runBlocking
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.employee.leave.domain.EmployeeLeave
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/employees")
class EmployeeLeaveController(
    private val addEmployeeLeaveHandler: AddEmployeeLeaveHandler,
    private val listEmployeeLeavesHandler: ListEmployeeLeavesHandler,
    private val deleteEmployeeLeaveHandler: DeleteEmployeeLeaveHandler,
    private val getLeaveCalendarHandler: GetLeaveCalendarHandler
) {

    @GetMapping("/{employeeId}/leaves")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
    fun listLeaves(@PathVariable employeeId: String): ResponseEntity<List<EmployeeLeaveResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val leaves = listEmployeeLeavesHandler.handle(principal.studioId, EmployeeId.fromString(employeeId))
        ResponseEntity.ok(leaves.map { it.toResponse() })
    }

    @PostMapping("/{employeeId}/leaves")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
    fun addLeave(
        @PathVariable employeeId: String,
        @RequestBody request: AddEmployeeLeaveRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val leaveType = request.leaveType?.let {
            runCatching { LeaveType.valueOf(it) }.getOrNull()
                ?: throw ValidationException("Nieprawidłowy typ urlopu: $it")
        } ?: LeaveType.ANNUAL

        val result = addEmployeeLeaveHandler.handle(AddEmployeeLeaveCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            leaveType = leaveType,
            startDate = request.startDate,
            endDate = request.endDate,
            note = request.note
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("leaveId" to result.leaveId.toString()))
    }

    @DeleteMapping("/{employeeId}/leaves/{leaveId}")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
    fun deleteLeave(
        @PathVariable employeeId: String,
        @PathVariable leaveId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteEmployeeLeaveHandler.handle(
            studioId = principal.studioId,
            employeeId = EmployeeId.fromString(employeeId),
            leaveId = UUID.fromString(leaveId),
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }

    // Deliberately NOT permission-gated: the calendar view (available to all logged-in
    // staff) needs per-day on-leave counts, mirroring the un-gated employee list endpoint.
    @GetMapping("/leaves/calendar")
    fun leaveCalendar(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate
    ): ResponseEntity<List<LeaveCalendarDayResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val days = getLeaveCalendarHandler.handle(principal.studioId, from, to)
        ResponseEntity.ok(days.map { day ->
            LeaveCalendarDayResponse(
                date = day.date.toString(),
                count = day.count,
                employees = day.employees.map { LeaveCalendarEmployeeResponse(it.id.toString(), it.fullName) }
            )
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request / Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class AddEmployeeLeaveRequest(
    val leaveType: String?,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val note: String? = null
)

data class EmployeeLeaveResponse(
    val id: String,
    val employeeId: String,
    val leaveType: String,
    val startDate: String,
    val endDate: String,
    val daysCount: Long,
    val note: String?,
    val createdAt: Instant
)

data class LeaveCalendarEmployeeResponse(
    val id: String,
    val fullName: String
)

data class LeaveCalendarDayResponse(
    val date: String,
    val count: Int,
    val employees: List<LeaveCalendarEmployeeResponse>
)

private fun EmployeeLeave.toResponse() = EmployeeLeaveResponse(
    id = id.toString(),
    employeeId = employeeId.value.toString(),
    leaveType = leaveType.name,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    daysCount = daysCount(),
    note = note,
    createdAt = createdAt
)
