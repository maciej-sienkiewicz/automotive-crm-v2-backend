package pl.detailing.crm.appointment.restore

import org.apache.coyote.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Handler for restoring a cancelled appointment.
 * Changes appointment status from CANCELLED back to CREATED.
 */
@Service
class RestoreAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: RestoreAppointmentCommand): RestoreAppointmentResult {
        // 1. Find appointment with studio isolation (excludes soft-deleted via repository query)
        val appointmentEntity = appointmentRepository.findByIdAndStudioId(
            id = command.appointmentId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Appointment not found: ${command.appointmentId}")

        // 2. Validate current status - can only restore CANCELLED appointments
        if (appointmentEntity.status != AppointmentStatus.CANCELLED) {
            throw BadRequestException("Cannot restore appointment that is not in CANCELLED status")
        }

        // 3. Change status back to CREATED
        val previousStatus = appointmentEntity.status.name
        appointmentEntity.status = AppointmentStatus.CREATED
        appointmentEntity.updatedBy = command.userId.value
        appointmentEntity.updatedAt = Instant.now()

        // 4. Save
        appointmentRepository.save(appointmentEntity)

        // 5. Audit log
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.APPOINTMENT,
            entityId = command.appointmentId.value.toString(),
            entityDisplayName = appointmentEntity.appointmentTitle,
            action = AuditAction.APPOINTMENT_RESTORED,
            changes = listOf(
                FieldChange("status", previousStatus, AppointmentStatus.CREATED.name)
            )
        ))

        return RestoreAppointmentResult(
            appointmentId = command.appointmentId
        )
    }
}

/**
 * Command to restore a cancelled appointment.
 */
data class RestoreAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)

/**
 * Result of restoring an appointment.
 */
data class RestoreAppointmentResult(
    val appointmentId: AppointmentId
)
