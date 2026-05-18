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
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

@Service
class LogWorkTimeHandler(
    private val employeeRepository: EmployeeRepository,
    private val workTimeRepository: WorkTimeEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: LogWorkTimeCommand): WorkTimeEntryId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Pracownik '${command.employeeId}' nie został znaleziony")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Nie można rejestrować czasu pracy dla zwolnionego pracownika")
        }

        if (!command.endTime.isAfter(command.startTime)) {
            throw ValidationException("Godzina zakończenia musi być późniejsza niż godzina rozpoczęcia")
        }

        val totalMinutes = Duration.between(command.startTime, command.endTime).toMinutes()
        val effectiveMinutes = totalMinutes - command.breakMinutes
        if (effectiveMinutes <= 0) {
            throw ValidationException("Efektywny czas pracy musi być dodatni po odjęciu przerwy")
        }

        val effectiveHours = BigDecimal(effectiveMinutes)
            .divide(BigDecimal(60), 2, RoundingMode.HALF_UP)

        val overtimeMultiplier = when (command.entryType) {
            WorkTimeEntryType.OVERTIME_150 -> BigDecimal("1.5")
            WorkTimeEntryType.OVERTIME_200 -> BigDecimal("2.0")
            WorkTimeEntryType.HOLIDAY_WORK -> BigDecimal("2.0")
            WorkTimeEntryType.NIGHT_WORK -> BigDecimal("1.2")
            else -> BigDecimal.ONE
        }

        val entry = WorkTimeEntry(
            id = WorkTimeEntryId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            date = command.date,
            startTime = command.startTime,
            endTime = command.endTime,
            breakMinutes = command.breakMinutes,
            effectiveHours = effectiveHours,
            entryType = command.entryType,
            overtimeMultiplier = overtimeMultiplier,
            notes = command.notes,
            approvedBy = null,
            approvedAt = null,
            status = WorkTimeStatus.PENDING,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        workTimeRepository.save(WorkTimeEntryEntity.fromDomain(entry))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.WORK_TIME_LOGGED,
            changes = listOf(
                FieldChange("date", null, command.date.toString()),
                FieldChange("effectiveHours", null, effectiveHours.toPlainString()),
                FieldChange("entryType", null, command.entryType.name)
            )
        ))

        entry.id
    }
}
