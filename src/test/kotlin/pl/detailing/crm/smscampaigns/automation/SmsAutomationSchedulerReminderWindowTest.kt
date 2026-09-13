package pl.detailing.crm.smscampaigns.automation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentQueryService
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentView
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsVisitQueryService
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Przypomnienia o wizycie respektują okno wysyłki 12–18 ([SendWindow]).
 *
 * Zgłoszenie z produkcji: SMS „Przypominamy o wizycie dnia …" przyszedł o 8:59. Wcześniej
 * przypomnienia szły [DeliveryPolicy.IMMEDIATE], omijając okno. Teraz moment wysyłki liczy
 * [SmsAutomationScheduler.reminderSendAt]: docelowo godzinę przed wizytą, ale jeśli to
 * wypada poza oknem — schodzi do ostatniego dozwolonego slotu przed wizytą, czyli dla
 * porannych wizyt na wieczór dnia poprzedniego. Nic nie wychodzi poza oknem.
 *
 * Zegar ustawiamy per test (Clock.fixed); okno to domyślne 12–18 Europe/Warsaw.
 * 2026-09-15 to wtorek w czasie letnim (UTC+2).
 */
class SmsAutomationSchedulerReminderWindowTest {

    private val zone = ZoneId.of("Europe/Warsaw")
    private val studioId = StudioId(UUID.randomUUID())

    private val configRepository: SmsAutomationConfigRepository = mockk()
    private val appointmentQueryService: SmsAppointmentQueryService = mockk()
    private val visitQueryService: SmsVisitQueryService = mockk(relaxed = true)
    private val smsLogRepository: SmsLogJpaRepository = mockk(relaxed = true)
    private val communicationGateway: OutboundCommunicationGateway = mockk()
    private val templateProcessor: SmsTemplateProcessor = mockk()
    private val visitRepository: VisitRepository = mockk(relaxed = true)
    private val communicationLogService: CommunicationLogService = mockk(relaxed = true)
    private val thankYouSmsRepository: ScheduledThankYouSmsRepository = mockk(relaxed = true)

    init {
        every { templateProcessor.process(any(), any()) } returns "Przypominamy o wizycie"
        every { smsLogRepository.existsByAppointmentIdAndTriggerType(any(), any()) } returns false
        every { smsLogRepository.save(any()) } answers { firstArg() }
        every { visitRepository.findByAppointmentIdAndStudioId(any(), any()) } returns null
        every { thankYouSmsRepository.existsByAppointmentId(any()) } returns false
        every { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } returns
            SmsDeliveryResult.success("ext-1")
        every { visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any()) } returns emptyList()
        every { appointmentQueryService.findWithSendReminderAndStartTimeBetween(any(), any()) } returns emptyList()
    }

    // ── Wizyta popołudniowa: przypomnienie w oknie, o czasie ───────────────────

    @Test
    fun `popoludniowa wizyta - przypomnienie idzie w slocie 60 min przed wizyta`() {
        givenAppointment(startsAt = warsaw(day = 15, hour = 16))

        // 15:00 = godzina przed wizytą 16:00, mieści się w oknie → wychodzi teraz.
        schedulerAt(warsaw(day = 15, hour = 15)).processPendingAutomations()

        verifySent()
    }

    @Test
    fun `popoludniowa wizyta - godzine za wczesnie jeszcze nic nie wychodzi`() {
        givenAppointment(startsAt = warsaw(day = 15, hour = 16))

        // 14:00: moment wysyłki (15:00) jeszcze nie nadszedł.
        schedulerAt(warsaw(day = 15, hour = 14)).processPendingAutomations()

        verifyNothingSent()
    }

    // ── Wizyta poranna: przypomnienie wieczorem dnia poprzedniego ─────────────

    @Test
    fun `poranna wizyta - przypomnienie wychodzi wieczorem dnia poprzedniego`() {
        // Wizyta jutro o 10:00. Godzina przed = 9:00, poza oknem → slot to dziś 18:00.
        givenAppointment(startsAt = warsaw(day = 16, hour = 10))

        schedulerAt(warsaw(day = 15, hour = 18)).processPendingAutomations()

        verifySent()
    }

    @Test
    fun `poranna wizyta - nie wychodzi w poludnie dnia poprzedniego, czeka na slot`() {
        givenAppointment(startsAt = warsaw(day = 16, hour = 10))

        // Dziś 12:00: okno otwarte, ale slot tej wizyty to dopiero dziś 18:00.
        schedulerAt(warsaw(day = 15, hour = 12)).processPendingAutomations()

        verifyNothingSent()
    }

    // ── Sedno zgłoszenia: nic poza oknem ───────────────────────────────────────

    @Test
    fun `nic nie wychodzi o 8-59 - dokladnie przypadek ze zgloszenia`() {
        // Wizyta dziś o 9:59, godzina przed = 8:59. Wcześniej właśnie o tej porze
        // wychodził SMS. Teraz o 8:59 okno jest zamknięte → cisza (przypomnienie
        // poszło wieczorem dnia poprzedniego).
        givenAppointment(startsAt = warsaw(day = 15, hour = 9, minute = 59))

        schedulerAt(warsaw(day = 15, hour = 8, minute = 59)).processPendingAutomations()

        verifyNothingSent()
    }

    @Test
    fun `nic nie wychodzi w srodku nocy`() {
        givenAppointment(startsAt = warsaw(day = 16, hour = 10))

        schedulerAt(warsaw(day = 15, hour = 3)).processPendingAutomations()

        verifyNothingSent()
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────

    private fun schedulerAt(now: Instant): SmsAutomationScheduler {
        val config = SmsAutomationConfig.defaultFor(studioId).copy(
            preVisit = SmsAutomationRule(
                enabled = true,
                offsetMinutes = 60,
                messageTemplate = "Przypominamy o wizycie dnia {{data}} o godz. {{godzina}}."
            )
        )
        every { configRepository.findAllWithAnyRuleEnabled() } returns listOf(config)
        every { configRepository.findByStudioId(any()) } returns config
        return SmsAutomationScheduler(
            configRepository, appointmentQueryService, visitQueryService, smsLogRepository,
            communicationGateway, templateProcessor, visitRepository, communicationLogService,
            thankYouSmsRepository, SendWindow.DEFAULT, Clock.fixed(now, ZoneOffset.UTC)
        )
    }

    private fun givenAppointment(startsAt: Instant) {
        every { appointmentQueryService.findByStudioIdAndStartTimeBetween(any(), any(), any()) } returns
            listOf(
                SmsAppointmentView(
                    appointmentId = UUID.randomUUID(),
                    customerId = UUID.randomUUID(),
                    appointmentStart = startsAt,
                    appointmentEnd = startsAt.plus(Duration.ofHours(2)),
                    customerFirstName = "Anna",
                    customerLastName = "Kowalska",
                    customerPhone = "534920205",
                    studioName = "Studio",
                    studioId = studioId.value,
                    isAllDay = false
                )
            )
    }

    private fun warsaw(day: Int, hour: Int, minute: Int = 0): Instant =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant()

    private fun verifySent() =
        verify(exactly = 1) {
            communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), DeliveryPolicy.SEND_WINDOW)
        }

    private fun verifyNothingSent() =
        verify(exactly = 0) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) }
}
