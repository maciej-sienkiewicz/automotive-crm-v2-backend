package pl.detailing.crm.appointment.cancel

import org.apache.coyote.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Handler for cancelling an appointment
 * Changes appointment status to CANCELLED
 */
@Service
class CancelAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: CancelAppointmentCommand): CancelAppointmentResult {
        // 1. Find appointment with studio isolation
        val appointmentEntity = appointmentRepository.findByIdAndStudioId(
            id = command.appointmentId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Appointment not found: ${command.appointmentId}")

        // 2. Validate current status - can only cancel CREATED appointments
        if (appointmentEntity.status == AppointmentStatus.CONVERTED) {
            throw BadRequestException("Cannot cancel appointment that has been converted to a visit")
        }

        if (appointmentEntity.status == AppointmentStatus.CANCELLED) {
            throw BadRequestException("Appointment is already cancelled")
        }

        // 3. Capture previous status and update to CANCELLED
        val previousStatus = appointmentEntity.status.name
        appointmentEntity.status = AppointmentStatus.CANCELLED
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
            action = AuditAction.APPOINTMENT_CANCELLED,
            changes = listOf(
                FieldChange("status", previousStatus, AppointmentStatus.CANCELLED.name)
            )
        ))

        return CancelAppointmentResult(
            appointmentId = command.appointmentId
        )
    }
}

/**
 * Command to cancel an appointment
 */
data class CancelAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)

/**
 * Result of cancelling an appointment
 */
data class CancelAppointmentResult(
    val appointmentId: AppointmentId
)
