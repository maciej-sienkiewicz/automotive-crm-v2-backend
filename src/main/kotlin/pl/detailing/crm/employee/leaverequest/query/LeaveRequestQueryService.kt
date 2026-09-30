package pl.detailing.crm.employee.leaverequest.query

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.PolishHolidays
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Filtr kolejki rozpatrujących. */
enum class LeaveRequestQueueFilter(val statuses: Set<LeaveRequestStatus>) {
    PENDING(setOf(LeaveRequestStatus.PENDING)),
    DECIDED(LeaveRequestStatus.DECIDED),
    ALL(LeaveRequestStatus.DECIDED + LeaveRequestStatus.PENDING)
}

/** Odczyty wniosków — bez zapisów, bez zmian statusu. */
@Service
class LeaveRequestQueryService(
    private val access: LeaveRequestAccess,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository
) {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    companion object {
        /** Kolejka bez stronicowania w kontrakcie — twardy sufit chroni przed studiem z tysiącami wniosków. */
        const val MAX_QUEUE = 500
    }

    data class MyRequests(
        val employeeId: UUID,
        val requests: List<LeaveRequestEntity>,
        val year: Int,
        val usedWorkingDays: Int,
        val pendingCount: Int
    )

    /**
     * Moje wnioski i podsumowanie roku. „Wykorzystane" liczymy z employee_leaves (urlop
     * wypoczynkowy, także na żądanie i wpisany ręcznie przez przełożonego), a nie z wniosków:
     * urlop jako fakt ma jedno źródło, to samo, które czyta kalendarz. Urlop na przełomie
     * lat liczy się do roku tylko dniami, które w nim leżą.
     */
    fun myRequests(studioId: StudioId, userId: UserId): MyRequests {
        val employee = access.employeeOf(studioId, userId)
        val today = LocalDate.now(warsaw)
        val yearStart = LocalDate.of(today.year, 1, 1)
        val yearEnd = LocalDate.of(today.year, 12, 31)
        val used = employeeLeaveRepository.findOverlappingOfEmployee(studioId.value, employee.id, yearStart, yearEnd)
            .filter { it.leaveType == LeaveType.ANNUAL }
            .sumOf { PolishHolidays.workingDaysBetween(maxOf(it.startDate, yearStart), minOf(it.endDate, yearEnd)) }
        return MyRequests(
            employeeId = employee.id,
            requests = leaveRequestRepository.findSubmittedByEmployee(studioId.value, employee.id),
            year = today.year,
            usedWorkingDays = used,
            pendingCount = leaveRequestRepository.countPendingOfEmployee(studioId.value, employee.id).toInt()
        )
    }

    data class Preview(val workingDays: Int, val holidays: List<PolishHolidays.Holiday>)

    /** Licznik dni na żywo w kreatorze — ta sama reguła, którą wniosek zamrozi przy utworzeniu. */
    fun preview(startDate: LocalDate, endDate: LocalDate): Preview {
        if (endDate.isBefore(startDate)) {
            throw ValidationException("Data zakończenia urlopu nie może być wcześniejsza niż data rozpoczęcia", field = "endDate")
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) >= 366) {
            throw ValidationException("Jeden wniosek może obejmować najwyżej rok", field = "endDate")
        }
        return Preview(
            workingDays = PolishHolidays.workingDaysBetween(startDate, endDate),
            holidays = PolishHolidays.weekdayHolidaysBetween(startDate, endDate)
        )
    }

    fun queue(studioId: StudioId, filter: LeaveRequestQueueFilter, employeeId: UUID?): List<LeaveRequestEntity> {
        val page = PageRequest.of(0, MAX_QUEUE)
        return if (employeeId == null) {
            leaveRequestRepository.findByStatuses(studioId.value, filter.statuses, page)
        } else {
            leaveRequestRepository.findByStatusesAndEmployee(studioId.value, filter.statuses, employeeId, page)
        }
    }

    /** Wszystkie oczekujące w studiu — licznik zakładki „Oczekujące" w kolejce. */
    fun pendingInStudio(studioId: StudioId): Int =
        leaveRequestRepository.countByStatus(studioId.value, LeaveRequestStatus.PENDING).toInt()

    /**
     * Oczekujące, które TEN użytkownik może rozpatrzyć — bez jego własnych wniosków.
     * Licznik przy „Pracownicy" i podpowiedź na Tablicy wołają o decyzję; nie mogą wołać
     * o taką, której ta osoba podjąć nie wolno.
     */
    fun pendingDecidableBy(principal: UserPrincipal): Int =
        leaveRequestRepository.countPendingDecidableBy(principal.studioId.value, principal.userId.value).toInt()
}
