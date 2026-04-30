package pl.detailing.crm.appointment.smsprefs

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import java.time.Instant

data class UpdateAppointmentSmsPreferencesCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val sendReminderSms: Boolean
)

@Service
class UpdateAppointmentSmsPreferencesHandler(
    private val appointmentRepository: AppointmentRepository,
    private val smsLogRepository: SmsLogJpaRepository
) {

    @Transactional
    fun handle(command: UpdateAppointmentSmsPreferencesCommand) {
        val appointment = appointmentRepository.findByIdAndStudioId(
            command.appointmentId.value,
            command.studioId.value
        ) ?: throw NotFoundException("Rezerwacja nie znaleziona")

        if (appointment.sendReminderSms == command.sendReminderSms) return

        val reminderAlreadySent = smsLogRepository.existsByAppointmentIdAndTriggerType(
            appointment.id,
            SmsTriggerType.PRE_VISIT
        )
        if (reminderAlreadySent) {
            throw ValidationException("Nie można zmienić preferencji — SMS z przypomnieniem został już wysłany")
        }

        appointment.sendReminderSms = command.sendReminderSms
        appointment.updatedBy = command.userId.value
        appointment.updatedAt = Instant.now()
        appointmentRepository.save(appointment)
    }
}
