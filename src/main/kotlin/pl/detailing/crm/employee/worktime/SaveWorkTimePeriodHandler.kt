package pl.detailing.crm.employee.worktime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.WorkTimeEntry
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.employee.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

data class SaveWorkTimePeriodCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val period: YearMonth,
    val regularEntries: List<RegularEntry>,
    val benefitEntries: List<BenefitEntry>
) {
    data class RegularEntry(val date: LocalDate, val hours: BigDecimal)
    data class BenefitEntry(val date: LocalDate, val benefitType: WorkTimeEntryType, val hours: BigDecimal)
}

@Service
class SaveWorkTimePeriodHandler(
    private val employeeRepository: EmployeeRepository,
    private val workTimeRepository: WorkTimeEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: SaveWorkTimePeriodCommand) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Employee '${command.employeeId}' not found")

        val periodFrom = command.period.atDay(1)
        val periodTo = command.period.atEndOfMonth()

        // Validate all dates belong to the period (422)
        val allDates = command.regularEntries.map { it.date } + command.benefitEntries.map { it.date }
        val invalidDates = allDates.filter { it.isBefore(periodFrom) || it.isAfter(periodTo) }
        if (invalidDates.isNotEmpty()) {
            throw UnprocessableEntityException(
                "Dates $invalidDates do not belong to period ${command.period}"
            )
        }

        // Validate hours > 0 (400)
        val invalidRegular = command.regularEntries.filter { it.hours <= BigDecimal.ZERO }
        val invalidBenefit = command.benefitEntries.filter { it.hours <= BigDecimal.ZERO }
        if (invalidRegular.isNotEmpty() || invalidBenefit.isNotEmpty()) {
            throw ValidationException("All hours values must be greater than 0")
        }

        // Load all entries for this period
        val allPeriodEntries = workTimeRepository.findByEmployeeIdAndDateRange(
            command.employeeId.value, command.studioId.value, periodFrom, periodTo
        )

        // 409: the whole period is approved (all entries are APPROVED, none PENDING)
        if (allPeriodEntries.isNotEmpty() && allPeriodEntries.all { it.status == WorkTimeStatus.APPROVED }) {
            throw ConflictException("Period ${command.period} is already fully approved and cannot be modified")
        }

        val pendingEntries = allPeriodEntries.filter { it.status == WorkTimeStatus.PENDING }

        // Build new entries from payload
        val now = Instant.now()
        val newEntries = mutableListOf<WorkTimeEntryEntity>()

        for (re in command.regularEntries) {
            newEntries.add(
                WorkTimeEntryEntity.fromDomain(
                    WorkTimeEntry(
                        id = WorkTimeEntryId.random(),
                        studioId = command.studioId,
                        employeeId = command.employeeId,
                        date = re.date,
                        startTime = LocalTime.MIDNIGHT,
                        endTime = LocalTime.MIDNIGHT,
                        breakMinutes = 0,
                        effectiveHours = re.hours,
                        entryType = WorkTimeEntryType.REGULAR,
                        overtimeMultiplier = BigDecimal.ONE,
                        notes = null,
                        approvedBy = null,
                        approvedAt = null,
                        status = WorkTimeStatus.PENDING,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        }

        for (be in command.benefitEntries) {
            val multiplier = when (be.benefitType) {
                WorkTimeEntryType.OVERTIME_150 -> BigDecimal("1.5")
                WorkTimeEntryType.OVERTIME_200, WorkTimeEntryType.HOLIDAY_WORK -> BigDecimal("2.0")
                WorkTimeEntryType.NIGHT_WORK -> BigDecimal("1.2")
                else -> BigDecimal.ONE
            }
            newEntries.add(
                WorkTimeEntryEntity.fromDomain(
                    WorkTimeEntry(
                        id = WorkTimeEntryId.random(),
                        studioId = command.studioId,
                        employeeId = command.employeeId,
                        date = be.date,
                        startTime = LocalTime.MIDNIGHT,
                        endTime = LocalTime.MIDNIGHT,
                        breakMinutes = 0,
                        effectiveHours = be.hours,
                        entryType = be.benefitType,
                        overtimeMultiplier = multiplier,
                        notes = null,
                        approvedBy = null,
                        approvedAt = null,
                        status = WorkTimeStatus.PENDING,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        }

        // Atomically: delete all PENDING entries for the period, then insert new ones
        workTimeRepository.deleteAll(pendingEntries)
        workTimeRepository.saveAll(newEntries)

        auditService.log(
            LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.EMPLOYEE,
                entityId = command.employeeId.value.toString(),
                entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
                action = AuditAction.WORK_TIME_PERIOD_SAVED,
                changes = listOf(
                    FieldChange("period", null, command.period.toString()),
                    FieldChange("regularEntries", null, command.regularEntries.size.toString()),
                    FieldChange("benefitEntries", null, command.benefitEntries.size.toString())
                )
            )
        )
    }
}
