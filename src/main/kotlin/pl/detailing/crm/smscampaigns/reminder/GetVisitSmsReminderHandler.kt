package pl.detailing.crm.smscampaigns.reminder

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository

data class GetVisitSmsReminderCommand(
    val studioId: StudioId,
    val visitId: VisitId
)

/**
 * Returns all reminders for a visit (active and historical).
 * The list is ordered by createdAt DESC — most recent first.
 */
@Service
class GetVisitSmsReminderHandler(
    private val reminderRepository: ScheduledSmsReminderRepository
) {
    fun handle(command: GetVisitSmsReminderCommand): List<ScheduledSmsReminder> =
        reminderRepository.findAllByVisitId(
            visitId = command.visitId.value,
            studioId = command.studioId.value
        )
}
