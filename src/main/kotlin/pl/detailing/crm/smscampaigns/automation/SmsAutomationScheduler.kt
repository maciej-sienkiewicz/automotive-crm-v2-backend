package pl.detailing.crm.smscampaigns.automation

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.shared.AppointmentId
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
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Scheduled orchestrator for automated SMS sending.
 *
 * Runs every minute and processes all studios that have at least one enabled rule.
 * Each rule is handled independently, and each reads the source that actually proves the
 * event happened:
 *   - PRE_VISIT:        [rule.offsetMinutes] before the start of a live booking
 *   - POST_VISIT:       [rule.offsetMinutes] after the customer collected the car
 *   - DELAYED_REMINDER: the same anchor, months out
 *
 * Only PRE_VISIT looks at appointments. The two rules that talk about a visit in the past
 * tense read COMPLETED visits, because an appointment reaching its end time says nothing
 * about whether the customer ever turned up.
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
    private val communicationLogService: CommunicationLogService,
    private val thankYouSmsRepository: ScheduledThankYouSmsRepository,
    private val sendWindow: SendWindow,
    /** Podmieniany w testach; produkcyjnie zegar systemowy (Spring bierze wartość domyślną). */
    private val clock: Clock = Clock.systemUTC()
) {

    companion object {
        private val logger = LoggerFactory.getLogger(SmsAutomationScheduler::class.java)

        /** Half-width of the matching window around the target time (in seconds). */
        private const val WINDOW_HALF_WIDTH_SECONDS = 60L

        /** Offset used for per-appointment on-demand reminders when studio automation is disabled. */
        private const val ON_DEMAND_PRE_VISIT_OFFSET_MINUTES = 60

        /**
         * Jak daleko w przód szukamy wizyt dla przypomnień PRE_VISIT. Przypomnienie może
         * zejść najwyżej o nocną przerwę okna przed moment „offset przed wizytą" (patrz
         * [reminderSendAt]), więc doba zapasu ponad offset wystarcza, by złapać wizytę,
         * której slot wysyłki (np. wieczór dnia poprzedniego) wypada właśnie teraz.
         */
        private val REMINDER_LOOKAHEAD_SLACK: Duration = Duration.ofHours(24)
    }

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    fun processPendingAutomations() {
        val now = clock.instant()
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
        // Poza oknem wysyłki nic nie leci — także przypomnienia „na życzenie".
        if (!sendWindow.contains(now)) return

        val candidates = appointmentQueryService.findWithSendReminderAndStartTimeBetween(
            now, reminderLookaheadEnd(now, ON_DEMAND_PRE_VISIT_OFFSET_MINUTES)
        )
        val due = dueReminders(candidates, ON_DEMAND_PRE_VISIT_OFFSET_MINUTES, now)

        if (due.isEmpty()) return

        logger.debug("SMS on-demand pre-visit: {} due candidate(s)", due.size)

        due.forEach { appointment ->
            val studioId = StudioId(appointment.studioId)
            // The per-appointment opt-in overrides the rule's on/off switch, but never
            // its text: with no template configured there is nothing to send.
            val rule = configRepository.findByStudioId(studioId)
                ?.preVisit?.copy(enabled = true)
                ?.takeIf { it.sendable }
                ?: return@forEach
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
        if (config.preVisit.sendable) {
            processPreVisitRule(studioId = config.studioId, rule = config.preVisit, now = now)
        }

        // POST_VISIT and DELAYED_REMINDER both count from the moment the customer drove
        // away, and both read completed visits. A booking that was never checked in has no
        // visit, so neither rule can fire for it — which is the whole point.
        listOf(
            config.postVisit to SmsTriggerType.POST_VISIT,
            config.delayedReminder to SmsTriggerType.DELAYED_REMINDER
        ).forEach { (rule, triggerType) ->
            if (!rule.sendable) return@forEach
            val targetPickupTime = now.minusSeconds(rule.offsetMinutes * 60L)
            processCompletedVisits(
                studioId = config.studioId,
                rule = rule,
                triggerType = triggerType,
                windowStart = targetPickupTime.minusSeconds(WINDOW_HALF_WIDTH_SECONDS),
                windowEnd = targetPickupTime.plusSeconds(WINDOW_HALF_WIDTH_SECONDS)
            )
        }
    }

    /**
     * Przypomnienia PRE_VISIT respektują okno wysyłki 12–18 (patrz [SendWindow]).
     *
     * Docelowo idą [SmsAutomationRule.offsetMinutes] przed wizytą, ale jeśli ten moment
     * wypada poza oknem, [reminderSendAt] schodzi do ostatniego dozwolonego slotu przed
     * wizytą — dla wizyt porannych jest to wieczór dnia poprzedniego, nie cisza nocna.
     *
     * Dlatego nie patrzymy już wąsko na „teraz + offset", tylko szerzej w przód i sami
     * liczymy moment wysyłki każdego kandydata: wizytę o 10:00 trzeba wypatrzeć już
     * poprzedniego wieczoru, żeby zdążyć wysłać przypomnienie w oknie.
     */
    private fun processPreVisitRule(studioId: StudioId, rule: SmsAutomationRule, now: Instant) {
        // Poza oknem nic nie wychodzi — to jest cała reguła „furtki". Kandydatów, których
        // slot wysyłki wypadł w tej chwili, złapiemy przy najbliższym otwarciu okna.
        if (!sendWindow.contains(now)) return

        val candidates = appointmentQueryService.findByStudioIdAndStartTimeBetween(
            studioId, now, reminderLookaheadEnd(now, rule.offsetMinutes)
        )
        val due = dueReminders(candidates, rule.offsetMinutes, now)

        if (due.isEmpty()) return

        logger.debug(
            "SMS automation: {} due candidate(s) for PRE_VISIT in studio={}",
            due.size, studioId
        )

        due.forEach { appointment ->
            dispatchSms(appointment, rule, SmsTriggerType.PRE_VISIT, studioId)
        }
    }

    // ── Kiedy wypada przypomnienie o wizycie ───────────────────────────────────

    /**
     * Kandydaci, których moment wysyłki już nadszedł ([reminderSendAt] ≤ [now]), a sama
     * wizyta jeszcze nie — porównanie „start po now" zostaje dublowane twardą granicą w
     * [dispatchSms], ale tutaj odsiewa większość pracy od razu.
     *
     * „≤ now", nie „== now": jeśli slot wysyłki minął (późno założona rezerwacja, przerwa
     * w działaniu schedulera), przypomnienie i tak wyjdzie przy najbliższym ticku w oknie,
     * dopóki wizyta jest jeszcze przed nami. Deduplikacja w [dispatchSms] pilnuje, żeby
     * poszło dokładnie raz.
     */
    private fun dueReminders(
        appointments: List<SmsAppointmentView>,
        offsetMinutes: Int,
        now: Instant
    ): List<SmsAppointmentView> =
        appointments.filter { appointment ->
            appointment.appointmentStart.isAfter(now) &&
                !reminderSendAt(appointment.appointmentStart, offsetMinutes).isAfter(now)
        }

    /**
     * Moment wysyłki przypomnienia: docelowo [offsetMinutes] przed wizytą, ale nigdy poza
     * oknem. Gdy moment docelowy wypada poza oknem, schodzimy do ostatniego dozwolonego
     * slotu przed nim ([SendWindow.lastSlotOnOrBefore]) — stąd „wieczór dnia poprzedniego"
     * dla porannych wizyt.
     */
    private fun reminderSendAt(appointmentStart: Instant, offsetMinutes: Int): Instant {
        val ideal = appointmentStart.minusSeconds(offsetMinutes * 60L)
        return if (sendWindow.contains(ideal)) ideal else sendWindow.lastSlotOnOrBefore(ideal)
    }

    /** Górna granica wyszukiwania wizyt: offset przed wizytą plus doba na nocną przerwę okna. */
    private fun reminderLookaheadEnd(now: Instant, offsetMinutes: Int): Instant =
        now.plusSeconds(offsetMinutes * 60L).plus(REMINDER_LOOKAHEAD_SLACK)

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

        // Twarda granica, niezależna od tego, jakie okno wybrało zapytanie: przypomnienie
        // o wizycie, która już trwa (albo minęła), jest gorsze niż żadne. Broni przed
        // offsetem ustawionym na 0, spóźnionym tickiem schedulera i każdą przyszłą zmianą
        // zapytania — zapytanie wybiera kandydatów, o wysyłce rozstrzyga ten warunek.
        val now = clock.instant()
        if (!appointment.appointmentStart.isAfter(now)) {
            logger.warn(
                "Skipping {} SMS for appointment={}: appointment started at {} (now={})",
                triggerType, appointment.appointmentId, appointment.appointmentStart, now
            )
            return
        }

        val phoneNumber = normalizePolishPhone(rawPhone)
        val message = templateProcessor.process(
            template = rule.messageTemplate,
            context = SmsTemplateContext(
                firstName = appointment.customerFirstName ?: "",
                lastName = appointment.customerLastName ?: "",
                appointmentStart = appointment.appointmentStart,
                allDay = appointment.isAllDay
            )
        )

        // SEND_WINDOW: przypomnienie o wizycie też słucha okna wysyłki. Scheduler wypuszcza
        // je dopiero w slocie policzonym przez reminderSendAt — a ten zawsze mieści się w
        // oknie — więc bramka wysyła od razu; gdyby jednak coś trafiło tu poza oknem, ma
        // zostać odłożone, a nie pójść do klienta o 8:59. To była przyczyna zgłoszenia:
        // wcześniej szło IMMEDIATE i omijało okno. Przypomnienie porannej wizyty schodzi
        // teraz na wieczór dnia poprzedniego (reminderSendAt), zamiast łamać ciszę.
        val result = communicationGateway.sendSms(
            customerId = appointment.customerId,
            studioId = studioId.value,
            phoneNumber = phoneNumber,
            message = message,
            context = "SmsAutomation trigger=$triggerType appointment=${appointment.appointmentId}",
            delivery = DeliveryPolicy.SEND_WINDOW
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
                appointmentId = AppointmentId(appointment.appointmentId),
                channel = CommunicationChannel.SMS,
                messageType = messageType,
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage,
                queuedMessageId = result.queuedMessageId
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

    private fun processCompletedVisits(
        studioId: StudioId,
        rule: SmsAutomationRule,
        triggerType: SmsTriggerType,
        windowStart: Instant,
        windowEnd: Instant
    ) {
        val visits = visitQueryService.findCompletedByStudioIdAndPickupDateBetween(studioId, windowStart, windowEnd)

        if (visits.isEmpty()) return

        logger.debug(
            "SMS automation: {} candidate(s) for {} in studio={}",
            visits.size, triggerType, studioId
        )

        visits.forEach { visit ->
            dispatchVisitSms(visit, rule, triggerType, studioId)
        }
    }

    private fun dispatchVisitSms(
        visit: SmsVisitView,
        rule: SmsAutomationRule,
        triggerType: SmsTriggerType,
        studioId: StudioId
    ) {
        val rawPhone = visit.customerPhone ?: run {
            logger.debug(
                "Skipping {} SMS for visit={}: customer has no phone",
                triggerType, visit.visitId
            )
            return
        }

        if (smsLogRepository.existsByAppointmentIdAndTriggerType(visit.appointmentId, triggerType)) {
            logger.debug(
                "Skipping {} SMS for visit={}: already sent",
                triggerType, visit.visitId
            )
            return
        }

        // Podziękowanie zaplanowane przy wydaniu pojazdu wygrywa z automatem — także wtedy,
        // gdy człowiek przy ladzie wybrał „nie wysyłaj". Automat liczy od odbioru pojazdu,
        // a odbiór odnotowuje się w systemie wtedy, kiedy jest chwila: stąd „dziękujemy za
        // wizytę" o 20:50. Wybrany termin (albo brak wysyłki) nie może przez to przepaść.
        if (triggerType == SmsTriggerType.POST_VISIT &&
            thankYouSmsRepository.existsByAppointmentId(visit.appointmentId)
        ) {
            logger.debug(
                "Skipping POST_VISIT SMS for visit={}: handover already decided when to thank the customer",
                visit.visitId
            )
            return
        }

        val phoneNumber = normalizePolishPhone(rawPhone)
        val message = templateProcessor.process(
            template = rule.messageTemplate,
            // {{data}} / {{godzina}} are the visit the customer remembers, not the moment
            // they collected the car.
            context = SmsTemplateContext(
                firstName = visit.customerFirstName ?: "",
                lastName = visit.customerLastName ?: "",
                appointmentStart = visit.scheduledDate,
                allDay = visit.isAllDay
            )
        )

        val result = communicationGateway.sendSms(
            customerId = visit.customerId,
            studioId = studioId.value,
            phoneNumber = phoneNumber,
            message = message,
            context = "SmsAutomation trigger=$triggerType visit=${visit.visitId}"
        )

        smsLogRepository.save(
            SmsLogEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                appointmentId = visit.appointmentId,
                triggerType = triggerType,
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
                messageType = visitMessageType(triggerType),
                recipientAddress = phoneNumber,
                subject = null,
                bodyContent = message,
                success = result.success,
                errorMessage = result.errorMessage,
                queuedMessageId = result.queuedMessageId
            )
        )

        if (result.success) {
            logger.info(
                "SMS sent | trigger={} visit={} phone={} externalId={}",
                triggerType, visit.visitId, phoneNumber, result.externalMessageId
            )
        } else {
            logger.warn(
                "SMS failed | trigger={} visit={} phone={} error={}",
                triggerType, visit.visitId, phoneNumber, result.errorMessage
            )
        }
    }

    private fun visitMessageType(triggerType: SmsTriggerType): CommunicationMessageType = when (triggerType) {
        SmsTriggerType.POST_VISIT -> CommunicationMessageType.SMS_AUTOMATION_POST_VISIT
        else -> CommunicationMessageType.SMS_AUTOMATION_DELAYED_REMINDER
    }
}
