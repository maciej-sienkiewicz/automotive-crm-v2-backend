package pl.detailing.crm.employee.payroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.domain.BonusEntry
import pl.detailing.crm.employee.domain.BonusEntryStatus
import pl.detailing.crm.employee.infrastructure.BonusEntryEntity
import pl.detailing.crm.employee.infrastructure.BonusEntryRepository
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.YearMonth

// ─────────────────────────────────────────────────────────────────────────────
// Commands
// ─────────────────────────────────────────────────────────────────────────────

data class AddBonusCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val period: YearMonth,
    val name: String,
    /** Positive = bonus, negative = deduction. In grosz (1/100 PLN). */
    val amountCents: Long,
    val notes: String?
)

data class DeleteBonusCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val employeeId: EmployeeId,
    val bonusEntryId: BonusEntryId
)

// ─────────────────────────────────────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────────────────────────────────────

@Service
class AddBonusHandler(
    private val employeeRepository: EmployeeRepository,
    private val bonusRepository: BonusEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: AddBonusCommand): BonusEntryId = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(command.employeeId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Employee '${command.employeeId}' not found")

        if (employeeEntity.status == EmployeeStatus.TERMINATED) {
            throw ValidationException("Cannot add a bonus for a terminated employee")
        }

        val bonus = BonusEntry(
            id = BonusEntryId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            period = command.period,
            name = command.name,
            amountCents = command.amountCents,
            status = BonusEntryStatus.PENDING,
            payrollEntryId = null,
            notes = command.notes,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        bonusRepository.save(BonusEntryEntity.fromDomain(bonus))

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            entityDisplayName = "${employeeEntity.firstName} ${employeeEntity.lastName}",
            action = AuditAction.BONUS_ADDED,
            changes = listOf(
                FieldChange("period", null, command.period.toString()),
                FieldChange("name", null, command.name),
                FieldChange("amountCents", null, command.amountCents.toString())
            )
        ))

        bonus.id
    }
}

@Service
class ListBonusesHandler(
    private val bonusRepository: BonusEntryRepository
) {
    suspend fun handle(
        employeeId: EmployeeId,
        studioId: StudioId,
        period: YearMonth?
    ): List<BonusEntry> = withContext(Dispatchers.IO) {
        if (period != null) {
            bonusRepository.findByEmployeeIdAndStudioIdAndPeriodOrderByCreatedAtAsc(
                employeeId.value, studioId.value, period.toString()
            ).map { it.toDomain() }
        } else {
            bonusRepository.findByEmployeeIdAndStudioIdOrderByPeriodDesc(
                employeeId.value, studioId.value
            ).map { it.toDomain() }
        }
    }
}

@Service
class DeleteBonusHandler(
    private val bonusRepository: BonusEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: DeleteBonusCommand) = withContext(Dispatchers.IO) {
        val entity = bonusRepository.findByIdAndStudioId(command.bonusEntryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Bonus entry '${command.bonusEntryId}' not found")

        if (entity.status == BonusEntryStatus.INCLUDED_IN_PAYROLL) {
            throw ValidationException("Cannot delete a bonus that has already been included in a payroll run. Void the payroll entry first.")
        }

        bonusRepository.delete(entity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = command.employeeId.value.toString(),
            action = AuditAction.BONUS_DELETED,
            changes = listOf(
                FieldChange("bonusEntryId", null, command.bonusEntryId.toString()),
                FieldChange("name", null, entity.name),
                FieldChange("amountCents", null, entity.amountCents.toString())
            )
        ))
    }
}
