package pl.detailing.crm.smscampaigns.thankyou

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSms
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsStatus
import pl.detailing.crm.communication.template.AppointmentAllDayLookup
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.smscampaigns.thankyou.domain.ThankYouSmsWindow
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Decyzja podjęta przy wydaniu pojazdu jest tą, która obowiązuje.
 *
 * Dwie rzeczy muszą być tu prawdziwe naraz: wybrana godzina nigdy nie wypada poza
 * dozwolonym oknem, a odznaczenie pola faktycznie wycisza automat — inaczej „nie
 * wysyłaj" byłoby tylko opóźnieniem tego samego SMS-a o kwadrans.
 */
class ScheduleThankYouSmsHandlerTest {

    private val visitRepository: VisitRepository = mockk()
    private val customerRepository: CustomerRepository = mockk()
    private val configRepository: SmsAutomationConfigRepository = mockk()
    private val templateProcessor: SmsTemplateProcessor = mockk()
    private val repository: ScheduledThankYouSmsRepository = mockk()
    private val window = ThankYouSmsWindow(SendWindow.DEFAULT)
    private val allDayLookup: AppointmentAllDayLookup = mockk { every { isAllDay(any(), any()) } returns false }

    private val handler = ScheduleThankYouSmsHandler(
        visitRepository,
        customerRepository,
        configRepository,
        templateProcessor,
        repository,
        window,
        allDayLookup
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val appointmentId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        every { configRepository.findByStudioId(studioId) } returns configWith(
            SmsAutomationRule(enabled = true, offsetMinutes = 30, messageTemplate = "Dziękujemy, {{imie}}!")
        )
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns visitEntity()
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns customerEntity("534920205")
        every { templateProcessor.process(any(), any()) } returns "Dziękujemy, Anna!"
        every { repository.existsByAppointmentId(appointmentId) } returns false
        every { repository.save(any()) } answers { firstArg() }
    }

    // ── Wysyłka zaplanowana ──────────────────────────────────────────────────

    @Test
    fun `planuje podziekowanie na wybrana godzine`() {
        val requested = at(16, 30)

        val saved = handler.handle(command(send = true, scheduledAt = requested))

        assertNotNull(saved)
        assertEquals(ScheduledThankYouSmsStatus.PENDING, saved!!.status)
        assertEquals(requested, saved.scheduledFor)
        assertEquals("Dziękujemy, Anna!", saved.messageContent)
    }

    @Test
    fun `godzina spoza okna wraca na najblizsza dozwolona`() {
        val saved = handler.handle(command(send = true, scheduledAt = at(22, 0)))

        assertTrue(window.contains(saved!!.scheduledFor!!))
    }

    @Test
    fun `brak godziny w zadaniu daje najblizszy dozwolony termin`() {
        val saved = handler.handle(command(send = true, scheduledAt = null))

        assertTrue(window.contains(saved!!.scheduledFor!!))
        assertTrue(saved.scheduledFor!!.isAfter(Instant.now()))
    }

    @Test
    fun `numer telefonu jest normalizowany w chwili planowania`() {
        // Późniejsza zmiana numeru w kartotece nie ma przepisywać zaplanowanej wysyłki.
        val saved = handler.handle(command(send = true, scheduledAt = at(14, 0)))

        assertEquals("+48534920205", saved!!.phoneNumber)
    }

    // ── Rezygnacja ───────────────────────────────────────────────────────────

    @Test
    fun `odznaczone pole zapisuje decyzje odmowna, ktora wycisza automat`() {
        val saved = handler.handle(command(send = false, scheduledAt = at(14, 0)))

        assertEquals(ScheduledThankYouSmsStatus.SKIPPED, saved!!.status)
        assertNull(saved.scheduledFor)
        assertNull(saved.messageContent)
        // Wpis MUSI powstać: bez niego automat POST_VISIT wyśle SMS-a mimo odmowy.
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `klient bez numeru konczy sie decyzja pominieta, a nie wisząca wysylka`() {
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns customerEntity(null)

        val saved = handler.handle(command(send = true, scheduledAt = at(14, 0)))

        assertEquals(ScheduledThankYouSmsStatus.SKIPPED, saved!!.status)
    }

    // ── Kiedy nie ma czego planować ──────────────────────────────────────────

    @Test
    fun `wylaczony szablon nie zapisuje zadnej decyzji`() {
        every { configRepository.findByStudioId(studioId) } returns configWith(
            SmsAutomationRule(enabled = false, offsetMinutes = 30, messageTemplate = "Dziękujemy!")
        )

        assertNull(handler.handle(command(send = true, scheduledAt = at(14, 0))))
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `wlaczony szablon bez tresci nie zapisuje zadnej decyzji`() {
        every { configRepository.findByStudioId(studioId) } returns configWith(
            SmsAutomationRule(enabled = true, offsetMinutes = 30, messageTemplate = "   ")
        )

        assertNull(handler.handle(command(send = true, scheduledAt = at(14, 0))))
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `studio bez konfiguracji SMS nie zapisuje zadnej decyzji`() {
        every { configRepository.findByStudioId(studioId) } returns null

        assertNull(handler.handle(command(send = true, scheduledAt = at(14, 0))))
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `druga decyzja dla tej samej rezerwacji nie nadpisuje pierwszej`() {
        every { repository.existsByAppointmentId(appointmentId) } returns true

        assertNull(handler.handle(command(send = true, scheduledAt = at(14, 0))))
        verify(exactly = 0) { repository.save(any()) }
    }

    // ── Treść ────────────────────────────────────────────────────────────────

    @Test
    fun `tresc powstaje z szablonu POST_VISIT ustawionego przez studio`() {
        val template = slot<String>()
        every { templateProcessor.process(capture(template), any()) } returns "gotowa treść"

        handler.handle(command(send = true, scheduledAt = at(14, 0)))

        assertEquals("Dziękujemy, {{imie}}!", template.captured)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun command(send: Boolean, scheduledAt: Instant?) = ScheduleThankYouSmsCommand(
        studioId = studioId,
        visitId = visitId,
        userId = userId,
        send = send,
        scheduledAt = scheduledAt
    )

    private fun configWith(postVisit: SmsAutomationRule) =
        SmsAutomationConfig.defaultFor(studioId).copy(postVisit = postVisit)

    private fun visitEntity(): VisitEntity = mockk<VisitEntity>(relaxed = true).also {
        every { it.appointmentId } returns appointmentId
        every { it.customerId } returns customerId
        every { it.scheduledDate } returns at(9, 0)
    }

    private fun customerEntity(phone: String?): CustomerEntity = mockk<CustomerEntity>(relaxed = true).also {
        every { it.phone } returns phone
        every { it.firstName } returns "Anna"
        every { it.lastName } returns "Kowalska"
    }

    /**
     * Godzina lokalna studia, zawsze w przyszłości względem „teraz" handlera.
     * Handler czyta zegar systemowy, więc data jest odsunięta o rok.
     */
    private fun at(hour: Int, minute: Int): Instant =
        LocalDateTime.of(
            LocalDate.now(window.zone).plusYears(1),
            LocalTime.of(hour, minute)
        ).atZone(window.zone).toInstant()
}
