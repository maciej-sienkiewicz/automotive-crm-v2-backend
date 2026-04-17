package pl.detailing.crm.smscampaigns.reminder.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import java.time.Instant
import java.util.UUID

@Component
class ScheduledSmsReminderRepositoryAdapter(
    private val jpa: ScheduledSmsReminderJpaRepository
) : ScheduledSmsReminderRepository {

    override fun save(reminder: ScheduledSmsReminder): ScheduledSmsReminder {
        val existing = jpa.findById(reminder.id).orElse(null)
        return if (existing != null) {
            existing.messageContent = reminder.messageContent
            existing.scheduledFor = reminder.scheduledFor
            existing.status = reminder.status
            existing.sentAt = reminder.sentAt
            existing.externalMessageId = reminder.externalMessageId
            existing.errorMessage = reminder.errorMessage
            existing.updatedAt = reminder.updatedAt
            jpa.save(existing).toDomain()
        } else {
            jpa.save(ScheduledSmsReminderEntity.fromDomain(reminder)).toDomain()
        }
    }

    override fun findById(id: UUID): ScheduledSmsReminder? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findPendingByVisitId(visitId: UUID, studioId: UUID): ScheduledSmsReminder? =
        jpa.findPendingByVisitId(visitId, studioId)?.toDomain()

    override fun findAllByVisitId(visitId: UUID, studioId: UUID): List<ScheduledSmsReminder> =
        jpa.findAllByVisitId(visitId, studioId).map { it.toDomain() }

    override fun findDueForDispatch(now: Instant): List<ScheduledSmsReminder> =
        jpa.findDueForDispatch(now).map { it.toDomain() }
}
