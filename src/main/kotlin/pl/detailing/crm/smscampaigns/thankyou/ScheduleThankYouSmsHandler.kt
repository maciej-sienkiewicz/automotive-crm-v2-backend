package pl.detailing.crm.smscampaigns.thankyou

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.template.AppointmentAllDayLookup
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.template.SmsTemplateContext
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSms
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsStatus
import pl.detailing.crm.smscampaigns.thankyou.domain.ThankYouSmsWindow
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Decyzja z okna „Wydanie pojazdu": czy i kiedy podziękować za wizytę.
 *
 * [scheduledAt] jest propozycją formularza — o ostatecznym terminie rozstrzyga
 * [ThankYouSmsWindow].
 */
data class ScheduleThankYouSmsCommand(
    val studioId: StudioId,
    val visitId: VisitId,
    val userId: UserId,
    val send: Boolean,
    val scheduledAt: Instant?
)

/**
 * Zapisuje decyzję o podziękowaniu po wizycie podjętą przy wydaniu pojazdu.
 *
 * Wpis powstaje także wtedy, gdy studio podziękowania NIE chce: to on wyłącza automat
 * POST_VISIT dla tej wizyty. Bez niego odznaczenie pola nic by nie dało — automat
 * wysłałby SMS-a i tak, po swoim opóźnieniu od odbioru pojazdu.
 *
 * Jedyne, czego wpis nie rozstrzyga, to treść i to, czy studio w ogóle takie wiadomości
 * wysyła: to nadal reguła `postVisit` z ustawień. Reguła wyłączona albo bez treści
 * oznacza, że nie ma czym ani po co planować — nie zapisujemy wtedy niczego, bo automat
 * przy takiej regule również milczy.
 */
@Service
class ScheduleThankYouSmsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val configRepository: SmsAutomationConfigRepository,
    private val templateProcessor: SmsTemplateProcessor,
    private val repository: ScheduledThankYouSmsRepository,
    private val window: ThankYouSmsWindow,
    private val allDayLookup: AppointmentAllDayLookup
) {
    private val logger = LoggerFactory.getLogger(ScheduleThankYouSmsHandler::class.java)

    @Transactional
    fun handle(command: ScheduleThankYouSmsCommand): ScheduledThankYouSms? {
        val rule = configRepository.findByStudioId(command.studioId)?.postVisit
        if (rule == null || !rule.sendable) {
            logger.debug(
                "Thank-you SMS not scheduled for visit={}: postVisit rule is not sendable",
                command.visitId
            )
            return null
        }

        val visit = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: run {
            logger.warn("Thank-you SMS not scheduled: visit={} not found", command.visitId)
            return null
        }

        // Ponowne wydanie tej samej wizyty nie ma prawa wysłać drugiego podziękowania.
        if (repository.existsByAppointmentId(visit.appointmentId)) {
            logger.debug(
                "Thank-you SMS already decided for visit={} — leaving the earlier decision in place",
                command.visitId
            )
            return null
        }

        val now = Instant.now()

        if (!command.send) return repository.save(decision(command, visit.customerId, visit.appointmentId, now))

        val customer = customerRepository.findByIdAndStudioId(
            id = visit.customerId,
            studioId = command.studioId.value
        )
        val phone = customer?.phone?.takeIf { it.isNotBlank() }

        // Klient bez numeru to nie jest błąd wydania pojazdu — auto już odjechało.
        // Zapisujemy decyzję jako pominiętą, żeby automat nie próbował tego samego.
        if (customer == null || phone == null) {
            logger.info(
                "Thank-you SMS skipped for visit={}: customer has no phone number",
                command.visitId
            )
            return repository.save(decision(command, visit.customerId, visit.appointmentId, now))
        }

        val message = templateProcessor.process(
            template = rule.messageTemplate,
            // {{data}} / {{godzina}} to wizyta, którą klient pamięta, a nie moment odbioru.
            context = SmsTemplateContext(
                firstName = customer.firstName ?: "",
                lastName = customer.lastName ?: "",
                appointmentStart = visit.scheduledDate,
                allDay = allDayLookup.isAllDay(visit.appointmentId, command.studioId.value)
            )
        )

        return repository.save(
            decision(command, visit.customerId, visit.appointmentId, now).copy(
                phoneNumber = normalizePolishPhone(phone),
                messageContent = message,
                scheduledFor = window.resolveSendAt(command.scheduledAt, now),
                status = ScheduledThankYouSmsStatus.PENDING
            )
        )
    }

    private fun decision(
        command: ScheduleThankYouSmsCommand,
        customerId: UUID,
        appointmentId: UUID,
        now: Instant
    ) = ScheduledThankYouSms(
        id = UUID.randomUUID(),
        studioId = command.studioId.value,
        visitId = command.visitId.value,
        appointmentId = appointmentId,
        customerId = customerId,
        phoneNumber = null,
        messageContent = null,
        scheduledFor = null,
        status = ScheduledThankYouSmsStatus.SKIPPED,
        sentAt = null,
        externalMessageId = null,
        errorMessage = null,
        createdBy = command.userId.value,
        createdAt = now,
        updatedAt = now
    )
}
