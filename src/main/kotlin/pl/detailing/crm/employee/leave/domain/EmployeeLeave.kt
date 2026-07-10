package pl.detailing.crm.employee.leave.domain

import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class LeaveType {
    ANNUAL,     // urlop wypoczynkowy
    SICK,       // zwolnienie lekarskie
    UNPAID,     // urlop bezpłatny
    SPECIAL,    // urlop okolicznościowy
    PARENTAL,   // urlop rodzicielski
    CARE        // opieka nad dzieckiem
}

data class EmployeeLeave(
    val id: UUID,
    val studioId: StudioId,
    val employeeId: EmployeeId,
    val leaveType: LeaveType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val note: String?,
    val createdBy: UserId,
    val createdAt: Instant
) {
    /** Liczba dni kalendarzowych urlopu (włącznie z oboma krańcami). */
    fun daysCount(): Long = ChronoUnit.DAYS.between(startDate, endDate) + 1

    fun covers(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)
}
