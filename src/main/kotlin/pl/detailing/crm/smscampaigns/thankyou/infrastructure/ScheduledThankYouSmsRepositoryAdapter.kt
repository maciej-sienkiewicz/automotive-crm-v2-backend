package pl.detailing.crm.smscampaigns.thankyou.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSms
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsStatus
import java.time.Instant
import java.util.UUID

@Component
class ScheduledThankYouSmsRepositoryAdapter(
    private val jpa: ScheduledThankYouSmsJpaRepository
) : ScheduledThankYouSmsRepository {

    override fun save(sms: ScheduledThankYouSms): ScheduledThankYouSms {
        val existing = jpa.findById(sms.id).orElse(null)
        return if (existing != null) {
            existing.phoneNumber = sms.phoneNumber
            existing.messageContent = sms.messageContent
            existing.scheduledFor = sms.scheduledFor
            existing.status = sms.status
            existing.sentAt = sms.sentAt
            existing.externalMessageId = sms.externalMessageId
            existing.errorMessage = sms.errorMessage
            existing.updatedAt = sms.updatedAt
            jpa.save(existing).toDomain()
        } else {
            jpa.save(ScheduledThankYouSmsEntity.fromDomain(sms)).toDomain()
        }
    }

    override fun existsByAppointmentId(appointmentId: UUID): Boolean =
        jpa.existsByAppointmentId(appointmentId)

    override fun findPendingByVisitId(visitId: UUID): ScheduledThankYouSms? =
        jpa.findPendingByVisitId(visitId)?.toDomain()

    override fun findDueForDispatch(now: Instant): List<ScheduledThankYouSms> =
        jpa.findDueForDispatch(now).map { it.toDomain() }

    override fun cancelPendingForVisit(visitId: UUID) {
        val pending = jpa.findPendingByVisitId(visitId) ?: return
        pending.status = ScheduledThankYouSmsStatus.CANCELLED
        pending.updatedAt = Instant.now()
        jpa.save(pending)
    }
}
