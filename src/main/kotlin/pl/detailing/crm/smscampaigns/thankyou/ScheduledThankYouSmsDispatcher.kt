package pl.detailing.crm.smscampaigns.thankyou

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogStatus
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSms
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsStatus
import java.time.Instant
import java.util.UUID

/**
 * Wysyła podziękowania zaplanowane przy wydaniu pojazdu, gdy nadejdzie ich godzina.
 *
 * Kopia decyzji trafia do `sms_send_log` pod triggerem POST_VISIT — tym samym, którego
 * używa automat. Dziennik SMS-ów rezerwacji ma pokazywać „SMS po wizycie" niezależnie od
 * tego, która ścieżka go wysłała.
 *
 * Zgody i limity kredytów pilnuje [OutboundCommunicationGateway]; zablokowana wysyłka
 * kończy się statusem FAILED z powodem, a nie ponowną próbą.
 */
@Service
class ScheduledThankYouSmsDispatcher(
    private val repository: ScheduledThankYouSmsRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val smsLogRepository: SmsLogJpaRepository
) {
    private val logger = LoggerFactory.getLogger(ScheduledThankYouSmsDispatcher::class.java)

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    fun dispatch() {
        val due = repository.findDueForDispatch(Instant.now())
        if (due.isEmpty()) return

        logger.info("Dispatching {} scheduled thank-you SMS", due.size)

        due.forEach { sms ->
            runCatching { dispatchOne(sms) }
                .onFailure { ex ->
                    logger.error("Unexpected error dispatching thank-you SMS={}: {}", sms.id, ex.message, ex)
                }
        }
    }

    private fun dispatchOne(sms: ScheduledThankYouSms) {
        val phoneNumber = sms.phoneNumber
        val message = sms.messageContent
        if (phoneNumber.isNullOrBlank() || message.isNullOrBlank()) {
            // PENDING bez numeru albo treści nie ma jak się udać i nie ma sensu ponawiać.
            logger.warn("Thank-you SMS={} has no recipient or no content — marking FAILED", sms.id)
            repository.save(sms.failed("Brak numeru telefonu lub treści wiadomości"))
            return
        }

        val result = try {
            communicationGateway.sendSms(
                customerId = sms.customerId,
                studioId = sms.studioId,
                phoneNumber = phoneNumber,
                message = message,
                context = "ScheduledThankYouSmsDispatcher thankYouSms=${sms.id} visit=${sms.visitId}"
            )
        } catch (ex: InsufficientSmsCreditsException) {
            logger.warn("Thank-you SMS skipped — no credits | id={} studio={}", sms.id, sms.studioId)
            repository.save(sms.failed(ex.message))
            return
        }

        val now = Instant.now()
        repository.save(
            sms.copy(
                status = if (result.success) ScheduledThankYouSmsStatus.SENT else ScheduledThankYouSmsStatus.FAILED,
                sentAt = now,
                externalMessageId = result.externalMessageId,
                errorMessage = result.errorMessage,
                updatedAt = now
            )
        )

        smsLogRepository.save(
            SmsLogEntity(
                id = UUID.randomUUID(),
                studioId = sms.studioId,
                appointmentId = sms.appointmentId,
                triggerType = SmsTriggerType.POST_VISIT,
                phoneNumber = phoneNumber,
                status = if (result.success) SmsLogStatus.SENT else SmsLogStatus.FAILED,
                externalMessageId = result.externalMessageId,
                errorMessage = result.errorMessage,
                sentAt = now
            )
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = StudioId(sms.studioId),
                customerId = CustomerId(sms.customerId),
                visitId = VisitId(sms.visitId),
                appointmentId = AppointmentId(sms.appointmentId),
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.SMS_AUTOMATION_POST_VISIT,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "Thank-you SMS sent | id={} visit={} phone={} externalId={}",
                sms.id, sms.visitId, phoneNumber, result.externalMessageId
            )
        } else {
            logger.warn(
                "Thank-you SMS failed | id={} visit={} phone={} error={}",
                sms.id, sms.visitId, phoneNumber, result.errorMessage
            )
        }
    }

    private fun ScheduledThankYouSms.failed(reason: String?) = copy(
        status = ScheduledThankYouSmsStatus.FAILED,
        sentAt = Instant.now(),
        errorMessage = reason,
        updatedAt = Instant.now()
    )
}
