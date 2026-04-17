package pl.detailing.crm.smscampaigns.reminder

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import java.time.Instant
import java.util.UUID

data class UpdateSmsReminderCommand(
    val studioId: StudioId,
    val reminderId: UUID,
    val messageContent: String,
    val scheduledFor: Instant
)

/**
 * Updates [messageContent] and/or [scheduledFor] of a PENDING reminder.
 * Only PENDING reminders can be modified; SENT/FAILED/CANCELLED are immutable.
 */
@Service
class UpdateSmsReminderHandler(
    private val reminderRepository: ScheduledSmsReminderRepository
) {
    companion object {
        private const val MAX_MESSAGE_LENGTH = 160
    }

    @Transactional
    fun handle(command: UpdateSmsReminderCommand): ScheduledSmsReminder {
        val reminder = reminderRepository.findById(command.reminderId)
            ?: throw EntityNotFoundException("Przypomnienie SMS nie znalezione: ${command.reminderId}")

        if (reminder.studioId != command.studioId.value) {
            throw EntityNotFoundException("Przypomnienie SMS nie znalezione: ${command.reminderId}")
        }

        if (reminder.status != ScheduledSmsReminderStatus.PENDING) {
            throw ValidationException(
                "Tylko oczekujące przypomnienia (PENDING) mogą być edytowane. " +
                "Aktualny status: ${reminder.status}"
            )
        }

        if (command.messageContent.isBlank()) throw ValidationException("Treść SMS nie może być pusta")
        if (command.messageContent.length > MAX_MESSAGE_LENGTH) {
            throw ValidationException(
                "Treść SMS przekracza $MAX_MESSAGE_LENGTH znaków (aktualnie: ${command.messageContent.length})"
            )
        }
        if (command.scheduledFor.isBefore(Instant.now())) {
            throw ValidationException("Data wysyłki musi być w przyszłości")
        }

        return reminderRepository.save(
            reminder.copy(
                messageContent = command.messageContent,
                scheduledFor = command.scheduledFor,
                updatedAt = Instant.now()
            )
        )
    }
}
