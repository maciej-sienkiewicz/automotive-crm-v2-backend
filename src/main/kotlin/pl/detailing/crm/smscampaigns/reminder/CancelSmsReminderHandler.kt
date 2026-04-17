package pl.detailing.crm.smscampaigns.reminder

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import java.time.Instant
import java.util.UUID

data class CancelSmsReminderCommand(
    val studioId: StudioId,
    val reminderId: UUID
)

/** Cancels a PENDING reminder before it is dispatched. */
@Service
class CancelSmsReminderHandler(
    private val reminderRepository: ScheduledSmsReminderRepository
) {
    @Transactional
    fun handle(command: CancelSmsReminderCommand) {
        val reminder = reminderRepository.findById(command.reminderId)
            ?: throw EntityNotFoundException("Przypomnienie SMS nie znalezione: ${command.reminderId}")

        if (reminder.studioId != command.studioId.value) {
            throw EntityNotFoundException("Przypomnienie SMS nie znalezione: ${command.reminderId}")
        }

        if (reminder.status != ScheduledSmsReminderStatus.PENDING) {
            throw ValidationException(
                "Tylko oczekujące przypomnienia (PENDING) mogą być anulowane. " +
                "Aktualny status: ${reminder.status}"
            )
        }

        reminderRepository.save(
            reminder.copy(
                status = ScheduledSmsReminderStatus.CANCELLED,
                updatedAt = Instant.now()
            )
        )
    }
}
