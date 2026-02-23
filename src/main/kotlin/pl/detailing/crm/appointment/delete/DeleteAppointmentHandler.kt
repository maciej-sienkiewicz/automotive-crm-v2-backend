package pl.detailing.crm.appointment.delete

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Handler for soft-deleting an appointment.
 * Sets deletedAt timestamp; the appointment is excluded from all queries afterwards.
 */
@Service
class DeleteAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: DeleteAppointmentCommand): DeleteAppointmentResult {
        // 1. Find appointment with studio isolation (excludes already soft-deleted via repository query)
        val appointmentEntity = appointmentRepository.findByIdAndStudioId(
            id = command.appointmentId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Appointment not found: ${command.appointmentId}")

        // 2. Soft delete - set deletedAt timestamp
        appointmentEntity.deletedAt = Instant.now()
        appointmentEntity.updatedBy = command.userId.value
        appointmentEntity.updatedAt = Instant.now()

        // 3. Save
        appointmentRepository.save(appointmentEntity)

        // 4. Audit log
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.APPOINTMENT,
            entityId = command.appointmentId.value.toString(),
            entityDisplayName = appointmentEntity.appointmentTitle,
            action = AuditAction.APPOINTMENT_DELETED,
            changes = emptyList()
        ))

        return DeleteAppointmentResult(
            appointmentId = command.appointmentId
        )
    }
}

/**
 * Command to soft-delete an appointment.
 */
data class DeleteAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)

/**
 * Result of deleting an appointment.
 */
data class DeleteAppointmentResult(
    val appointmentId: AppointmentId
)
