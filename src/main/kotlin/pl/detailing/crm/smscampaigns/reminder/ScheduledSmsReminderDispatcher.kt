package pl.detailing.crm.smscampaigns.reminder

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.consent.MarketingConsentChecker
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import pl.detailing.crm.smscampaigns.provider.SmsProvider
import java.time.Instant

/**
 * Runs every minute and dispatches any PENDING reminders whose [scheduledFor] has passed.
 *
 * Each reminder is sent atomically: status flips to SENT or FAILED within the same
 * transaction, preventing double-sends even under concurrent runs (which can't happen
 * because @Scheduled is single-threaded by default in Spring).
 *
 * The result is also logged to the shared [CommunicationLogService] so it appears
 * on the customer's communication timeline.
 */
@Service
class ScheduledSmsReminderDispatcher(
    private val reminderRepository: ScheduledSmsReminderRepository,
    private val smsProvider: SmsProvider,
    private val communicationLogService: CommunicationLogService,
    private val marketingConsentChecker: MarketingConsentChecker
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
        if (!marketingConsentChecker.canSend(
                customerId = reminder.customerId,
                studioId = reminder.studioId,
                channel = MarketingChannel.SMS,
                context = "ScheduledSmsReminderDispatcher reminder=${reminder.id} visit=${reminder.visitId}"
            )) {
            reminderRepository.save(reminder.copy(
                status = ScheduledSmsReminderStatus.FAILED,
                sentAt = Instant.now(),
                errorMessage = "Brak zgody na komunikację SMS",
                updatedAt = Instant.now()
            ))
            return
        }

        val result = smsProvider.send(reminder.phoneNumber, reminder.messageContent)

        val updated = reminder.copy(
            status = if (result.success) ScheduledSmsReminderStatus.SENT else ScheduledSmsReminderStatus.FAILED,
            sentAt = Instant.now(),
            externalMessageId = result.externalMessageId,
            errorMessage = result.errorMessage,
            updatedAt = Instant.now()
        )
        reminderRepository.save(updated)

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
