package pl.detailing.crm.employee.domain

import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class WorkTimeEntry(
    val id: WorkTimeEntryId,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val breakMinutes: Int,
    val effectiveHours: BigDecimal,
    val entryType: WorkTimeEntryType,
    val overtimeMultiplier: BigDecimal,
    val notes: String?,
    val approvedBy: UserId?,
    val approvedAt: Instant?,
    val status: WorkTimeStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)
