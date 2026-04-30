package pl.detailing.crm.smscampaigns.reminder

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import java.time.Instant

/**
 * Runs every minute and dispatches any PENDING reminders whose [scheduledFor] has passed.
 *
 * Consent enforcement is handled transparently by [OutboundCommunicationGateway].
 * A blocked send is treated as FAILED with an appropriate error message.
 */
@Service
class ScheduledSmsReminderDispatcher(
    private val reminderRepository: ScheduledSmsReminderRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(ScheduledSmsReminderDispatcher::class.java)

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    fun dispatch() {
        val now = Instant.now()
        val due = reminderRepository.findDueForDispatch(now)

        if (due.isEmpty()) return

        logger.info("Dispatching {} scheduled SMS reminder(s)", due.size)

        due.forEach { reminder ->
            runCatching { dispatchOne(reminder) }
                .onFailure { ex ->
                    logger.error(
                        "Unexpected error dispatching reminder={}: {}",
                        reminder.id, ex.message, ex
                    )
                }
        }
    }

    private fun dispatchOne(reminder: ScheduledSmsReminder) {
        val result = communicationGateway.sendSms(
            customerId = reminder.customerId,
            studioId = reminder.studioId,
            phoneNumber = reminder.phoneNumber,
            message = reminder.messageContent,
            context = "ScheduledSmsReminderDispatcher reminder=${reminder.id} visit=${reminder.visitId}"
        )

        reminderRepository.save(reminder.copy(
            status = if (result.success) ScheduledSmsReminderStatus.SENT else ScheduledSmsReminderStatus.FAILED,
            sentAt = Instant.now(),
            externalMessageId = result.externalMessageId,
            errorMessage = result.errorMessage,
            updatedAt = Instant.now()
        ))

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = StudioId(reminder.studioId),
                customerId = CustomerId(reminder.customerId),
                visitId = VisitId(reminder.visitId),
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.SMS_AUTOMATION_DELAYED_REMINDER,
                recipientAddress = reminder.phoneNumber,
                subject = null,
                bodyContent = reminder.messageContent,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SMS sent | reminder={} visit={} phone={} externalId={}",
                reminder.id, reminder.visitId, reminder.phoneNumber, result.externalMessageId
            )
        } else {
            logger.warn(
                "SMS failed | reminder={} visit={} phone={} error={}",
                reminder.id, reminder.visitId, reminder.phoneNumber, result.errorMessage
            )
        }
    }
}
