package pl.detailing.crm.appointment.title

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdateAppointmentTitleHandler(
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(command: UpdateAppointmentTitleCommand) {
        val appointmentEntity = appointmentRepository.findByIdAndStudioId(
            id = command.appointmentId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Appointment not found: ${command.appointmentId}")

        val oldTitle = appointmentEntity.appointmentTitle
        appointmentEntity.appointmentTitle = command.title
        appointmentEntity.updatedBy = command.userId.value
        appointmentEntity.updatedAt = Instant.now()
        appointmentRepository.save(appointmentEntity)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName,
            module = AuditModule.APPOINTMENT,
            entityId = command.appointmentId.value.toString(),
            entityDisplayName = oldTitle ?: command.title,
            action = AuditAction.UPDATE,
            changes = listOf(FieldChange("appointmentTitle", oldTitle, command.title)),
            metadata = emptyMap()
        ))
    }
}

data class UpdateAppointmentTitleCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val title: String?
)
