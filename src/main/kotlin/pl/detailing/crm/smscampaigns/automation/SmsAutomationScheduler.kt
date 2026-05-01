package pl.detailing.crm.smscampaigns.automation

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
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType.*
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentQueryService
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentView
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogStatus
import pl.detailing.crm.smscampaigns.infrastructure.SmsVisitQueryService
import pl.detailing.crm.smscampaigns.infrastructure.SmsVisitView
import pl.detailing.crm.smscampaigns.template.SmsTemplateContext
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Scheduled orchestrator for automated SMS sending.
 *
 * Runs every minute and processes all studios that have at least one enabled rule.
 * Each rule is handled independently:
 *   - PRE_VISIT:  fires [rule.offsetMinutes] before appointment start
 *   - POST_VISIT: fires [rule.offsetMinutes] after appointment end
 *
 * Deduplication is enforced via the [SmsLogJpaRepository]: each
 * (appointmentId, triggerType) pair is recorded after a successful dispatch.
 * A subsequent run that lands in the same window will skip already-logged entries.
 *
 * SRP: this class *only* orchestrates — template rendering, SMS dispatch,
 * appointment querying, and config persistence each live in their own classes.
 */
@Service
class SmsAutomationScheduler(
    private val configRepository: SmsAutomationConfigRepository,
    private val appointmentQueryService: SmsAppointmentQueryService,
    private val visitQueryService: SmsVisitQueryService,
    private val smsLogRepository: SmsLogJpaRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val templateProcessor: SmsTemplateProcessor,
    private val visitRepository: VisitRepository,
    private val communicationLogService: CommunicationLogService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(SmsAutomationScheduler::class.java)

        /** Half-width of the matching window around the target time (in seconds). */
        private const val WINDOW_HALF_WIDTH_SECONDS = 60L

        /** Offset used for per-appointment on-demand reminders when studio automation is disabled. */
        private const val ON_DEMAND_PRE_VISIT_OFFSET_MINUTES = 60L
    }

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    fun processPendingAutomations() {
        val now = Instant.now()
        val activeConfigs = configRepository.findAllWithAnyRuleEnabled()

        if (activeConfigs.isNotEmpty()) {
            logger.debug("SMS automation tick: {} studio(s) with active rules", activeConfigs.size)
            activeConfigs.forEach { config ->
                runCatching { processConfig(config, now) }
                    .onFailure { ex ->
                        logger.error(
                            "Error processing SMS automation for studio={}: {}",
                            config.studioId, ex.message, ex
                        )
                    }
            }
        }

        runCatching { processOnDemandPreVisitReminders(now) }
            .onFailure { ex ->
                logger.error("Error processing on-demand pre-visit reminders: {}", ex.message, ex)
            }
    }

    /**
     * Sends PRE_VISIT reminders for appointments where the user explicitly requested
     * a reminder SMS ([AppointmentEntity.sendReminderSms] = true), independent of
     * whether the studio has the PRE_VISIT automation rule enabled.
     *
     * Uses a fixed [ON_DEMAND_PRE_VISIT_OFFSET_MINUTES] offset. Deduplication via
     * [SmsLogJpaRepository] prevents double-sends when the studio automation already fired.
     */
    private fun processOnDemandPreVisitReminders(now: Instant) {
        val targetTime = now.plusSeconds(ON_DEMAND_PRE_VISIT_OFFSET_MINUTES * 60)
        val windowStart = targetTime.minusSeconds(WINDOW_HALF_WIDTH_SECONDS)
        val windowEnd = targetTime.plusSeconds(WINDOW_HALF_WIDTH_SECONDS)

        val appointments = appointmentQueryService.findWithSendReminderAndStartTimeBetween(windowStart, windowEnd)

        if (appointments.isEmpty()) return

        logger.debug("SMS on-demand pre-visit: {} candidate(s)", appointments.size)

        appointments.forEach { appointment ->
            val studioId = StudioId(appointment.studioId)
            val config = configRepository.findByStudioId(studioId)
                ?: SmsAutomationConfig.defaultFor(studioId)
            val rule = config.preVisit.copy(enabled = true)
            runCatching { dispatchSms(appointment, rule, SmsTriggerType.PRE_VISIT, studioId) }
                .onFailure { ex ->
                    logger.error(
                        "Error dispatching on-demand pre-visit SMS for appointment={}: {}",
                        appointment.appointmentId, ex.message, ex
                    )
                }
        }
    }

    private fun processConfig(config: SmsAutomationConfig, now: Instant) {
        if (config.preVisit.enabled) {
            val targetTime = now.plusSeconds(config.preVisit.offsetMinutes * 60L)
            processRule(
                studioId = config.studioId,
                rule = config.preVisit,
                triggerType = SmsTriggerType.PRE_VISIT,
                windowStart = targetTime.minusSeconds(WINDOW_HALF_WIDTH_SECONDS),
                windowEnd = targetTime.plusSeconds(WINDOW_HALF_WIDTH_SECONDS),
                useStartTime = true
            )
        }

        if (config.postVisit.enabled) {
            val targetTime = now.minusSeconds(config.postVisit.offsetMinutes * 60L)
            processRule(
                studioId = config.studioId,
                rule = config.postVisit,
                triggerType = SmsTriggerType.POST_VISIT,
                windowStart = targetTime.minusSeconds(WINDOW_HALF_WIDTH_SECONDS),
                windowEnd = targetTime.plusSeconds(WINDOW_HALF_WIDTH_SECONDS),
                useStartTime = false
            )
        }

        if (config.delayedReminder.enabled) {
            val targetPickupTime = now.minusSeconds(config.delayedReminder.offsetMinutes * 60L)
            processDelayedReminders(
                studioId = config.studioId,
                rule = config.delayedReminder,
                windowStart = targetPickupTime.minusSeconds(WINDOW_HALF_WIDTH_SECONDS),
                windowEnd = targetPickupTime.plusSeconds(WINDOW_HALF_WIDTH_SECONDS)
            )
        }
    }

    private fun processRule(
        studioId: StudioId,
        rule: SmsAutomationRule,
        triggerType: SmsTriggerType,
        windowStart: Instant,
        windowEnd: Instant,
        useStartTime: Boolean
    ) {
        val appointments = if (useStartTime) {
            appointmentQueryService.findByStudioIdAndStartTimeBetween(studioId, windowStart, windowEnd)
        } else {
            appointmentQueryService.findByStudioIdAndEndTimeBetween(studioId, windowStart, windowEnd)
        }

        if (appointments.isEmpty()) return

        logger.debug(
            "SMS automation: {} candidate(s) for {} in studio={}",
            appointments.size, triggerType, studioId
        )

        appointments.forEach { appointment ->
            dispatchSms(appointment, rule, triggerType, studioId)
        }
    }

    private fun dispatchSms(
        appointment: SmsAppointmentView,
        rule: SmsAutomationRule,
        triggerType: SmsTriggerType,
        studioId: StudioId
    ) {
        val rawPhone = appointment.customerPhone ?: run {
            logger.debug(
                "Skipping {} SMS for appointment={}: customer has no phone",
                triggerType, appointment.appointmentId
            )
            return
        }

        if (smsLogRepository.existsByAppointmentIdAndTriggerType(appointment.appointmentId, triggerType)) {
            logger.debug(
                "Skipping {} SMS for appointment={}: already sent",
                triggerType, appointment.appointmentId
            )
            return
        }

        val phoneNumber = normalizePolishPhone(rawPhone)
        val message = templateProcessor.process(
            template = rule.messageTemplate,
            context = SmsTemplateContext(
                firstName = appointment.customerFirstName ?: "",
                appointmentStart = appointment.appointmentStart,
                studioName = appointment.studioName
            )
        )

        val result = communicationGateway.sendSms(
            customerId = appointment.customerId,
            studioId = studioId.value,
            phoneNumber = phoneNumber,
            message = message,
            context = "SmsAutomation trigger=$triggerType appointment=${appointment.appointmentId}"
        )

        smsLogRepository.save(
            SmsLogEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                appointmentId = appointment.appointmentId,
                triggerType = triggerType,
                phoneNumber = phoneNumber,
                status = if (result.success) SmsLogStatus.SENT else SmsLogStatus.FAILED,
                externalMessageId = result.externalMessageId,
                errorMessage = result.errorMessage,
                sentAt = Instant.now()
            )
        )

        // Resolve visitId if the appointment has already been converted to a visit.
        // For PRE_VISIT triggers this is often null (visit not created yet).
        val visitId = visitRepository.findByAppointmentIdAndStudioId(
            appointment.appointmentId,
            studioId.value
        )?.let { VisitId(it.id) }

        val messageType = when (triggerType) {
            PRE_VISIT -> CommunicationMessageType.SMS_AUTOMATION_PRE_VISIT
            POST_VISIT -> CommunicationMessageType.SMS_AUTOMATION_POST_VISIT
            DELAYED_REMINDER -> CommunicationMessageType.SMS_AUTOMATION_DELAYED_REMINDER
            BOOKING_CONFIRMATION -> CommunicationMessageType.SMS_BOOKING_CONFIRMATION
        }

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = studioId,
                customerId = CustomerId(appointment.customerId),
                visitId = visitId,
                channel = CommunicationChannel.SMS,
                messageType = messageType,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SMS sent | trigger={} appointment={} phone={} externalId={}",
                triggerType, appointment.appointmentId, phoneNumber, result.externalMessageId
            )
        } else {
            logger.warn(
                "SMS failed | trigger={} appointment={} phone={} error={}",
                triggerType, appointment.appointmentId, phoneNumber, result.errorMessage
            )
        }
    }

    private fun processDelayedReminders(
        studioId: StudioId,
        rule: SmsAutomationRule,
        windowStart: Instant,
        windowEnd: Instant
    ) {
        val visits = visitQueryService.findByStudioIdAndPickupDateBetween(studioId, windowStart, windowEnd)

        if (visits.isEmpty()) return

        logger.debug(
            "SMS delayed-reminder: {} candidate(s) for studio={}",
            visits.size, studioId
        )

        visits.forEach { visit ->
            dispatchDelayedReminderSms(visit, rule, studioId)
        }
    }

    private fun dispatchDelayedReminderSms(
        visit: SmsVisitView,
        rule: SmsAutomationRule,
        studioId: StudioId
    ) {
        val rawPhone = visit.customerPhone ?: run {
            logger.debug(
                "Skipping DELAYED_REMINDER SMS for visit={}: customer has no phone",
                visit.visitId
            )
            return
        }

        if (smsLogRepository.existsByAppointmentIdAndTriggerType(visit.appointmentId, SmsTriggerType.DELAYED_REMINDER)) {
            logger.debug(
                "Skipping DELAYED_REMINDER SMS for visit={}: already sent",
                visit.visitId
            )
            return
        }

        val phoneNumber = normalizePolishPhone(rawPhone)
        val message = templateProcessor.process(
            template = rule.messageTemplate,
            context = SmsTemplateContext(
                firstName = visit.customerFirstName ?: "",
                appointmentStart = visit.pickupDate,
                studioName = visit.studioName
            )
        )

        val result = communicationGateway.sendSms(
            customerId = visit.customerId,
            studioId = studioId.value,
            phoneNumber = phoneNumber,
            message = message,
            context = "SmsAutomation trigger=DELAYED_REMINDER visit=${visit.visitId}"
        )

        smsLogRepository.save(
            SmsLogEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                appointmentId = visit.appointmentId,
                triggerType = SmsTriggerType.DELAYED_REMINDER,
                phoneNumber = phoneNumber,
                status = if (result.success) SmsLogStatus.SENT else SmsLogStatus.FAILED,
                externalMessageId = result.externalMessageId,
                errorMessage = result.errorMessage,
                sentAt = Instant.now()
            )
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = studioId,
                customerId = CustomerId(visit.customerId),
                visitId = VisitId(visit.visitId),
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.SMS_AUTOMATION_DELAYED_REMINDER,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SMS sent | trigger=DELAYED_REMINDER visit={} phone={} externalId={}",
                visit.visitId, phoneNumber, result.externalMessageId
            )
        } else {
            logger.warn(
                "SMS failed | trigger=DELAYED_REMINDER visit={} phone={} error={}",
                visit.visitId, phoneNumber, result.errorMessage
            )
        }
    }
}
