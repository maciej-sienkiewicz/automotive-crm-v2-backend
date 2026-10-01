package pl.detailing.crm.worktime

import io.mockk.every
import io.mockk.mockk
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.worktime.attendance.AttendanceSheetRepository
import pl.detailing.crm.worktime.attendance.AttendanceSheetService
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * Karty czasu pracy na mockach, z rejestrem osób jednego studia: [WorkTimeService]
 * i [WorkTimeMonthService] na tych samych repozytoriach, zdarzenia zbierane w [events].
 *
 * Repozytoria są „luźne" (relaxed), ale wszystko, co zwraca encję albo null, jest
 * zaślepione wprost — test ma widzieć brak karty jako brak, a nie jako pustą atrapę.
 */
class WorkTimeTestKit(val studio: StudioId = StudioId.random(), today: LocalDate = LocalDate.of(2026, 9, 15)) {

    val entries = mockk<WorkTimeEntryRepository>(relaxed = true)
    val periods = mockk<WorkTimePeriodRepository>(relaxed = true)
    val users = mockk<UserRepository>(relaxed = true)
    val roles = mockk<RoleRepository>(relaxed = true)
    val employees = mockk<EmployeeRepository>(relaxed = true)
    val leaves = mockk<EmployeeLeaveRepository>(relaxed = true)
    val sheets = mockk<AttendanceSheetRepository>(relaxed = true)
    val sheetService = mockk<AttendanceSheetService>(relaxed = true)
    val auditService = mockk<AuditService>(relaxed = true)
    val audited = mutableListOf<AuditEvent>()
    val events = mutableListOf<Any>()

    private val trackedRole = mockk<RoleEntity>().also {
        every { it.id } returns UUID.randomUUID()
        every { it.trackWorkTime } returns true
    }

    private val registeredUsers = mutableListOf<UserEntity>()
    private val registeredEmployees = mutableListOf<EmployeeEntity>()
    private val storedPeriods = mutableListOf<WorkTimePeriodEntity>()
    private val storedEntries = mutableListOf<WorkTimeEntryEntity>()
    private val storedLeaves = mutableListOf<EmployeeLeaveEntity>()

    val service = WorkTimeService(
        entries, periods,
        mockk<PermissionCheckService>(relaxed = true),
        auditService,
        mockk<AuditActorResolver>(relaxed = true),
        mockk<BusinessEventPublisher>(relaxed = true),
        employees, leaves, users, sheetService,
        ApplicationEventPublisher { events += it }
    )

    val monthService = WorkTimeMonthService(
        periods, entries, users, roles, employees, leaves, sheets, sheetService, service,
        ApplicationEventPublisher { events += it }
    )

    init {
        val clock = Clock.fixed(today.atTime(12, 0).atZone(ZoneId.of("Europe/Warsaw")).toInstant(), ZoneId.of("Europe/Warsaw"))
        service.clock = clock
        monthService.clock = clock

        every { auditService.recordSync(capture(audited)) } returns Unit
        every { roles.findByStudioId(studio.value) } returns listOf(trackedRole)
        every { users.findByStudioId(studio.value) } answers { registeredUsers.toList() }
        every { users.findActiveByStudioId(studio.value) } answers { registeredUsers.filter { it.isActive } }
        every { users.findByIdAndStudioId(any(), any()) } answers {
            registeredUsers.firstOrNull { it.id == firstArg<UUID>() && it.studioId == secondArg<UUID>() }
        }
        every { employees.findByStudioId(studio.value) } answers { registeredEmployees.toList() }
        every { employees.findByStudioIdAndUserId(any(), any()) } answers {
            registeredEmployees.firstOrNull { it.studioId == firstArg<UUID>() && it.userId == secondArg<UUID>() }
        }

        every { periods.findByUserIdAndStudioIdAndPeriod(any(), any(), any()) } answers {
            storedPeriods.firstOrNull { it.userId == firstArg() && it.studioId == secondArg() && it.period == thirdArg() }
        }
        every { periods.findByUserIdAndPeriod(any(), any()) } answers {
            storedPeriods.firstOrNull { it.userId == firstArg() && it.period == secondArg() }
        }
        every { periods.findByStudioIdAndPeriod(any(), any()) } answers {
            storedPeriods.filter { it.studioId == firstArg() && it.period == secondArg() }
        }
        every { periods.findByStudioIdAndStatus(any(), any()) } answers {
            storedPeriods.filter { it.studioId == firstArg() && it.status == secondArg<PeriodStatus>() }
        }
        every { periods.findByStudioIdOrderByUserIdAndPeriodDesc(any()) } answers {
            storedPeriods.filter { it.studioId == firstArg() }
        }
        every { periods.save(any()) } answers {
            val period = firstArg<WorkTimePeriodEntity>()
            if (storedPeriods.none { it === period }) storedPeriods += period
            period
        }
        every { periods.markReminded(any(), any(), any()) } answers {
            val period = storedPeriods.first { it.id == firstArg() }
            val threshold = thirdArg<Instant>()
            if (period.remindedAt == null || !period.remindedAt!!.isAfter(threshold)) {
                period.remindedAt = secondArg()
                1
            } else 0
        }

        every { entries.findByUserIdAndDate(any(), any()) } answers {
            storedEntries.firstOrNull { it.userId == firstArg() && it.date == secondArg() }
        }
        every { entries.findByUserIdAndStudioIdAndDateBetween(any(), any(), any(), any()) } answers {
            val from = thirdArg<LocalDate>()
            val to = arg<LocalDate>(3)
            storedEntries.filter { it.userId == firstArg() && it.studioId == secondArg() && !it.date.isBefore(from) && !it.date.isAfter(to) }
        }
        every { entries.findByUserIdAndDateBetween(any(), any(), any()) } answers {
            storedEntries.filter { it.userId == firstArg() && !it.date.isBefore(secondArg()) && !it.date.isAfter(thirdArg()) }
        }
        every { entries.findByStudioIdAndDateBetween(any(), any(), any()) } answers {
            storedEntries.filter { it.studioId == firstArg() && !it.date.isBefore(secondArg()) && !it.date.isAfter(thirdArg()) }
        }
        every { entries.save(any()) } answers {
            val entry = firstArg<WorkTimeEntryEntity>()
            if (storedEntries.none { it === entry }) storedEntries += entry
            entry
        }

        every { leaves.findOverlappingOfEmployee(any(), any(), any(), any()) } answers {
            storedLeaves.filter {
                it.studioId == firstArg() && it.employeeId == secondArg() &&
                    !it.startDate.isAfter(arg(3)) && !it.endDate.isBefore(thirdArg())
            }
        }
        every { leaves.findOverlappingRange(any(), any(), any()) } answers {
            storedLeaves.filter { it.studioId == firstArg() && !it.startDate.isAfter(thirdArg()) && !it.endDate.isBefore(secondArg()) }
        }
        every { sheets.findByStudioIdAndPeriod(any(), any()) } returns emptyList()
        every { sheets.findAllOfStudio(any()) } returns emptyList()
    }

    /** Konto pracownika z rolą liczącą czas pracy (i rekordem pracownika, o ile [withEmployee]). */
    fun user(
        firstName: String,
        lastName: String,
        tracked: Boolean = true,
        isOwner: Boolean = false,
        active: Boolean = true,
        withEmployee: Boolean = true,
        studioId: StudioId = studio,
        createdAt: Instant = Instant.parse("2025-01-01T00:00:00Z")
    ): UserEntity {
        val user = UserEntity(
            id = UUID.randomUUID(), studioId = studioId.value, email = "${firstName.lowercase()}@example.com",
            phoneNumber = "+48500000000", passwordHash = "x", firstName = firstName, lastName = lastName,
            isOwner = isOwner, isActive = active, createdAt = createdAt,
            customRoleId = if (tracked) trackedRole.id else UUID.randomUUID()
        )
        if (studioId == studio) registeredUsers += user
        if (withEmployee) {
            registeredEmployees += EmployeeEntity(
                id = UUID.randomUUID(), studioId = studioId.value, userId = user.id, firstName = firstName,
                lastName = lastName, phone = null, email = null, createdBy = UUID.randomUUID(), updatedBy = UUID.randomUUID()
            )
        }
        return user
    }

    fun employeeOf(user: UserEntity): EmployeeEntity = registeredEmployees.first { it.userId == user.id }

    fun period(
        user: UserEntity,
        month: YearMonth,
        status: PeriodStatus,
        submittedAt: Instant? = if (status != PeriodStatus.DRAFT) Instant.parse("2026-09-30T10:00:00Z") else null,
        studioId: StudioId = StudioId(user.studioId)
    ): WorkTimePeriodEntity = WorkTimePeriodEntity(
        userId = user.id, studioId = studioId.value, period = month.toString(), status = status, submittedAt = submittedAt
    ).also { storedPeriods += it }

    fun entry(user: UserEntity, date: LocalDate, minutes: Int = 480): WorkTimeEntryEntity =
        WorkTimeEntryEntity(userId = user.id, studioId = user.studioId, date = date, minutes = minutes)
            .also { storedEntries += it }

    fun leave(user: UserEntity, from: LocalDate, to: LocalDate, type: LeaveType = LeaveType.ANNUAL): EmployeeLeaveEntity =
        EmployeeLeaveEntity(
            id = UUID.randomUUID(), studioId = user.studioId, employeeId = employeeOf(user).id, leaveType = type,
            startDate = from, endDate = to, note = null, createdBy = UUID.randomUUID()
        ).also { storedLeaves += it }

    fun entriesOf(user: UserEntity): List<WorkTimeEntryEntity> = storedEntries.filter { it.userId == user.id }

    fun periodOf(user: UserEntity, month: YearMonth): WorkTimePeriodEntity? =
        storedPeriods.firstOrNull { it.userId == user.id && it.period == month.toString() }

    fun id(user: UserEntity): UserId = UserId(user.id)
}
