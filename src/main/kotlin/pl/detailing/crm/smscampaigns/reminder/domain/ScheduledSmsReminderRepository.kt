package pl.detailing.crm.smscampaigns.reminder.domain

import java.time.Instant
import java.util.UUID

interface ScheduledSmsReminderRepository {

    fun save(reminder: ScheduledSmsReminder): ScheduledSmsReminder

    fun findById(id: UUID): ScheduledSmsReminder?

    /** Returns the single active (PENDING) reminder for this visit, or null. */
    fun findPendingByVisitId(visitId: UUID, studioId: UUID): ScheduledSmsReminder?

    /** Returns all reminders for a visit regardless of status (for history view). */
    fun findAllByVisitId(visitId: UUID, studioId: UUID): List<ScheduledSmsReminder>

    /** Returns all PENDING reminders whose scheduledFor <= now (ready to dispatch). */
    fun findDueForDispatch(now: Instant): List<ScheduledSmsReminder>
}
