package pl.detailing.crm.smscampaigns.automation

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentQueryService
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentView
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogStatus
import pl.detailing.crm.smscampaigns.infrastructure.SmsVisitQueryService
import pl.detailing.crm.smscampaigns.infrastructure.SmsVisitView
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Przypomnienie o wizycie ma sens tylko PRZED wizytą.
 *
 * Zapytanie wybiera kandydatów po oknie „teraz + offset ± 60 s", ale to nie jest gwarancja:
 * offset 0, spóźniony tick schedulera albo zmiana zapytania mogą podać wizytę, która już
 * trwa. Te testy pilnują ostatniej linii obrony w samym automacie, niezależnie od tego,
 * skąd przyszła lista — i tego, że przypomnienie zakotwiczone w godzinie wizyty nigdy nie
 * ląduje w kolejce na 12:00, a reguły liczone od odbioru pojazdu owszem.
 */
class SmsAutomationSchedulerPreVisitTest {

    private val now: Instant = Instant.parse("2026-09-15T06:00:00Z")

    private val configRepository: SmsAutomationConfigRepository = mockk()
    private val appointmentQueryService: SmsAppointmentQueryService = mockk()
    private val visitQueryService: SmsVisitQueryService = mockk()
    private val smsLogRepository: SmsLogJpaRepository = mockk(relaxed = true)
    private val communicationGateway: OutboundCommunicationGateway = mockk()
    private val templateProcessor: SmsTemplateProcessor = mockk()
    private val visitRepository: VisitRepository = mockk(relaxed = true)
    private val communicationLogService: CommunicationLogService = mockk(relaxed = true)
    private val thankYouSmsRepository: ScheduledThankYouSmsRepository = mockk(relaxed = true)

    private val scheduler = SmsAutomationScheduler(
        configRepository, appointmentQueryService, visitQueryService, smsLogRepository,
        communicationGateway, templateProcessor, visitRepository, communicationLogService,
        thankYouSmsRepository, Clock.fixed(now, ZoneOffset.UTC)
    )

    private val studioId = StudioId(UUID.randomUUID())

    init {
        every { templateProcessor.process(any(), any()) } returns "Do zobaczenia jutro!"
        every { smsLogRepository.existsByAppointmentIdAndTriggerType(any(), any()) } returns false
        // Relaxed mock generycznego save() zwraca Object, a Kotlin rzutuje wynik — stub musi oddać encję.
        every { smsLogRepository.save(any()) } answers { firstArg() }
        every { visitRepository.findByAppointmentIdAndStudioId(any(), any()) } returns null
        every { thankYouSmsRepository.existsByAppointmentId(any()) } returns false
        every { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } returns
            SmsDeliveryResult.success("ext-1")
    }

    // ── Wizyta, która już się zaczęła ────────────────────────────────────────

    @Test
    fun `nie wysyla przypomnienia o wizycie, ktora juz sie zaczela`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        givenRuleCandidates(appointment(startsAt = now.minus(Duration.ofMinutes(1))))

        scheduler.processPendingAutomations()

        verifyNothingSent()
    }

    @Test
    fun `nie wysyla przypomnienia o wizycie zaczynajacej sie dokladnie teraz`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        givenRuleCandidates(appointment(startsAt = now))

        scheduler.processPendingAutomations()

        verifyNothingSent()
    }

    @Test
    fun `offset 0 w konfiguracji nie przepycha przypomnienia po starcie wizyty`() {
        // Okno zapytania dla offsetu 0 to [now-60s, now+60s) — zapytanie MOŻE zwrócić
        // wizytę sprzed pół minuty. Automat ma ją odrzucić sam.
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 0))
        givenRuleCandidates(
            appointment(startsAt = now.minusSeconds(30)),
            appointment(startsAt = now.plusSeconds(30))
        )

        scheduler.processPendingAutomations()

        val sent = mutableListOf<UUID>()
        verify(exactly = 1) { communicationGateway.sendSms(capture(sent), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `przypomnienie na zyczenie tez nie wychodzi po starcie wizyty`() {
        givenStudioRule(preVisit = rule(enabled = false, offsetMinutes = 60))
        givenOnDemandCandidates(appointment(startsAt = now.minus(Duration.ofMinutes(5))))

        scheduler.processPendingAutomations()

        verifyNothingSent()
    }

    // ── Wizyta w przyszłości: wysyłka od ręki, nigdy przez kolejkę ───────────

    @Test
    fun `przypomnienie o wizycie za godzine wychodzi od razu, z pominieciem okna wysylki`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        val appointment = appointment(startsAt = now.plus(Duration.ofMinutes(60)))
        givenRuleCandidates(appointment)

        scheduler.processPendingAutomations()

        val delivery = slot<DeliveryPolicy>()
        verify(exactly = 1) {
            communicationGateway.sendSms(
                appointment.customerId, studioId.value, "+48534920205", "Do zobaczenia jutro!", any(), any(), capture(delivery)
            )
        }
        assertEquals(DeliveryPolicy.IMMEDIATE, delivery.captured)
    }

    @Test
    fun `przypomnienie na zyczenie wychodzi mimo wylaczonej reguly studia`() {
        givenStudioRule(preVisit = rule(enabled = false, offsetMinutes = 60))
        givenOnDemandCandidates(appointment(startsAt = now.plus(Duration.ofMinutes(60))))

        scheduler.processPendingAutomations()

        verify(exactly = 1) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), DeliveryPolicy.IMMEDIATE) }
    }

    @Test
    fun `przypomnienie na zyczenie bez szablonu w ustawieniach nie ma czego wyslac`() {
        givenStudioRule(preVisit = rule(enabled = false, offsetMinutes = 60, template = ""))
        givenOnDemandCandidates(appointment(startsAt = now.plus(Duration.ofMinutes(60))))

        scheduler.processPendingAutomations()

        verifyNothingSent()
    }

    @Test
    fun `reguly liczone od odbioru pojazdu ida domyslna sciezka i czekaja na okno`() {
        givenStudioRule(postVisit = rule(enabled = true, offsetMinutes = 30))
        every { visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any()) } returns
            listOf(completedVisit())

        scheduler.processPendingAutomations()

        verify(exactly = 1) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), DeliveryPolicy.SEND_WINDOW) }
    }

    // ── Dedupe i braki ───────────────────────────────────────────────────────

    @Test
    fun `wizyta, o ktorej juz przypomniano, nie dostaje drugiego SMS-a`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        val appointment = appointment(startsAt = now.plus(Duration.ofMinutes(60)))
        givenRuleCandidates(appointment)
        every {
            smsLogRepository.existsByAppointmentIdAndTriggerType(appointment.appointmentId, SmsTriggerType.PRE_VISIT)
        } returns true

        scheduler.processPendingAutomations()

        verifyNothingSent()
    }

    @Test
    fun `ta sama wizyta w regule studia i na zyczenie dostaje jeden SMS`() {
        // Pierwsza ścieżka zapisuje sms_send_log, druga widzi wpis i milczy.
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        val appointment = appointment(startsAt = now.plus(Duration.ofMinutes(60)))
        givenRuleCandidates(appointment)
        givenOnDemandCandidates(appointment)
        val logged = mutableSetOf<UUID>()
        every { smsLogRepository.existsByAppointmentIdAndTriggerType(any(), any()) } answers { firstArg<UUID>() in logged }
        every { smsLogRepository.save(any()) } answers { firstArg<SmsLogEntity>().also { logged += it.appointmentId } }

        scheduler.processPendingAutomations()

        verify(exactly = 1) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `klient bez numeru nie dostaje niczego i nic nie laduje w dzienniku`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        givenRuleCandidates(appointment(startsAt = now.plus(Duration.ofMinutes(60)), phone = null))

        scheduler.processPendingAutomations()

        verifyNothingSent()
    }

    // ── Co zostaje po wysyłce ────────────────────────────────────────────────

    @Test
    fun `udana wysylka zostawia wpis dedupe i wpis w dzienniku komunikacji`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        val appointment = appointment(startsAt = now.plus(Duration.ofMinutes(60)))
        givenRuleCandidates(appointment)

        scheduler.processPendingAutomations()

        val log = slot<SmsLogEntity>()
        verify { smsLogRepository.save(capture(log)) }
        assertEquals(appointment.appointmentId, log.captured.appointmentId)
        assertEquals(SmsTriggerType.PRE_VISIT, log.captured.triggerType)
        assertEquals(SmsLogStatus.SENT, log.captured.status)
        assertEquals("ext-1", log.captured.externalMessageId)

        val record = slot<RecordCommunicationCommand>()
        verify { communicationLogService.record(capture(record)) }
        assertEquals(CommunicationMessageType.SMS_AUTOMATION_PRE_VISIT, record.captured.messageType)
        assertEquals(appointment.appointmentId, record.captured.appointmentId?.value)
        assertEquals(true, record.captured.success)
        assertEquals(null, record.captured.queuedMessageId)
    }

    @Test
    fun `nieudana wysylka tez zostawia wpis dedupe - blad dostawcy nie jest ponawiany co minute`() {
        givenStudioRule(preVisit = rule(enabled = true, offsetMinutes = 60))
        givenRuleCandidates(appointment(startsAt = now.plus(Duration.ofMinutes(60))))
        every { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } returns
            SmsDeliveryResult.failure("SMSAPI: invalid number")

        scheduler.processPendingAutomations()

        val log = slot<SmsLogEntity>()
        verify { smsLogRepository.save(capture(log)) }
        assertEquals(SmsLogStatus.FAILED, log.captured.status)
        assertEquals("SMSAPI: invalid number", log.captured.errorMessage)
    }

    @Test
    fun `odlozona wiadomosc po wizycie trafia do dziennika jako zakolejkowana`() {
        givenStudioRule(postVisit = rule(enabled = true, offsetMinutes = 30))
        every { visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any()) } returns
            listOf(completedVisit())
        val queuedId = UUID.randomUUID()
        every { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } returns
            SmsDeliveryResult.queued(queuedId, now.plus(Duration.ofHours(6)))

        scheduler.processPendingAutomations()

        val record = slot<RecordCommunicationCommand>()
        verify { communicationLogService.record(capture(record)) }
        assertEquals(queuedId, record.captured.queuedMessageId)
        assertEquals(true, record.captured.success)
    }

    // ── Odporność ────────────────────────────────────────────────────────────

    @Test
    fun `brak kredytow przy jednym przypomnieniu na zyczenie nie blokuje nastepnego`() {
        givenStudioRule(preVisit = rule(enabled = false, offsetMinutes = 60))
        val first = appointment(startsAt = now.plus(Duration.ofMinutes(60)))
        val second = appointment(startsAt = now.plus(Duration.ofMinutes(60)))
        givenOnDemandCandidates(first, second)
        every { communicationGateway.sendSms(first.customerId, any(), any(), any(), any(), any(), any()) } throws
            InsufficientSmsCreditsException("Brak kredytów SMS")

        scheduler.processPendingAutomations()

        verify(exactly = 1) { communicationGateway.sendSms(second.customerId, any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `awaria w jednym studiu nie zatrzymuje pozostalych`() {
        val broken = SmsAutomationConfig.defaultFor(StudioId(UUID.randomUUID()))
            .copy(preVisit = rule(enabled = true, offsetMinutes = 60))
        val healthy = SmsAutomationConfig.defaultFor(studioId)
            .copy(preVisit = rule(enabled = true, offsetMinutes = 60))
        every { configRepository.findAllWithAnyRuleEnabled() } returns listOf(broken, healthy)
        every { configRepository.findByStudioId(any()) } returns healthy
        every { appointmentQueryService.findWithSendReminderAndStartTimeBetween(any(), any()) } returns emptyList()
        every { appointmentQueryService.findByStudioIdAndStartTimeBetween(broken.studioId, any(), any()) } throws
            IllegalStateException("db down")
        every { appointmentQueryService.findByStudioIdAndStartTimeBetween(studioId, any(), any()) } returns
            listOf(appointment(startsAt = now.plus(Duration.ofMinutes(60))))

        scheduler.processPendingAutomations()

        verify(exactly = 1) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun verifyNothingSent() {
        verify(exactly = 0) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { smsLogRepository.save(any()) }
        verify(exactly = 0) { communicationLogService.record(any()) }
    }

    private fun rule(enabled: Boolean, offsetMinutes: Int, template: String = "Do zobaczenia {{data}}") =
        SmsAutomationRule(enabled = enabled, offsetMinutes = offsetMinutes, messageTemplate = template)

    private val off = SmsAutomationRule(enabled = false, offsetMinutes = 60, messageTemplate = "")

    private fun givenStudioRule(preVisit: SmsAutomationRule = off, postVisit: SmsAutomationRule = off) {
        val config = SmsAutomationConfig.defaultFor(studioId).copy(
            preVisit = preVisit, postVisit = postVisit, delayedReminder = off
        )
        every { configRepository.findAllWithAnyRuleEnabled() } returns listOf(config)
        every { configRepository.findByStudioId(any()) } returns config
        every { appointmentQueryService.findByStudioIdAndStartTimeBetween(any(), any(), any()) } returns emptyList()
        every { appointmentQueryService.findWithSendReminderAndStartTimeBetween(any(), any()) } returns emptyList()
        every { visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any()) } returns emptyList()
    }

    private fun givenRuleCandidates(vararg appointments: SmsAppointmentView) {
        every { appointmentQueryService.findByStudioIdAndStartTimeBetween(studioId, any(), any()) } returns appointments.toList()
    }

    private fun givenOnDemandCandidates(vararg appointments: SmsAppointmentView) {
        every { appointmentQueryService.findWithSendReminderAndStartTimeBetween(any(), any()) } returns appointments.toList()
    }

    private fun appointment(startsAt: Instant, phone: String? = "534920205") = SmsAppointmentView(
        appointmentId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        appointmentStart = startsAt,
        appointmentEnd = startsAt.plus(Duration.ofHours(2)),
        customerFirstName = "Anna",
        customerLastName = "Kowalska",
        customerPhone = phone,
        studioName = "Studio",
        studioId = studioId.value
    )

    private fun completedVisit() = SmsVisitView(
        visitId = UUID.randomUUID(),
        appointmentId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        pickupDate = now.minus(Duration.ofMinutes(30)),
        scheduledDate = now.minus(Duration.ofHours(3)),
        customerFirstName = "Anna",
        customerLastName = "Kowalska",
        customerPhone = "534920205",
        studioName = "Studio"
    )
}
