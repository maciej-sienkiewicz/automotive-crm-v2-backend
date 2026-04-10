package pl.detailing.crm.employee.payroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.employee.infrastructure.PayrollEntryRepository
import pl.detailing.crm.shared.*
import java.time.Instant

data class ConfirmPayrollCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String?,
    val payrollEntryId: PayrollEntryId,
    val markAsPaid: Boolean = false,
    val totalNet: Money? = null,
    val employerCostTotal: Money? = null
)

@Service
class ConfirmPayrollHandler(
    private val payrollRepository: PayrollEntryRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: ConfirmPayrollCommand) = withContext(Dispatchers.IO) {
        val entity = payrollRepository.findByIdAndStudioId(command.payrollEntryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Payroll entry '${command.payrollEntryId}' not found")

        val requiredStatus = if (command.markAsPaid) PayrollStatus.CONFIRMED else PayrollStatus.DRAFT
        if (entity.status != requiredStatus) {
            throw ValidationException("Payroll entry status must be ${requiredStatus.name} for this operation")
        }

        val oldStatus = entity.status
        val newStatus = if (command.markAsPaid) PayrollStatus.PAID else PayrollStatus.CONFIRMED

        entity.status = newStatus
        entity.confirmedBy = command.userId.value
        entity.confirmedAt = Instant.now()
        entity.updatedAt = Instant.now()
        command.totalNet?.let { entity.totalNet = it.amountInCents }
        command.employerCostTotal?.let { entity.employerCostTotal = it.amountInCents }

        payrollRepository.save(entity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = entity.employeeId.toString(),
            action = if (command.markAsPaid) AuditAction.PAYROLL_PAID else AuditAction.PAYROLL_CONFIRMED,
            changes = listOf(
                FieldChange("status", oldStatus.name, newStatus.name),
                FieldChange("payrollId", null, command.payrollEntryId.toString()),
                FieldChange("period", null, entity.period)
            )
        ))
    }
}
