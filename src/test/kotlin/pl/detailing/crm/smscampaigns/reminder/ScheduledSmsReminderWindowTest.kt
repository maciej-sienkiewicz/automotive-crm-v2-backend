package pl.detailing.crm.smscampaigns.reminder

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/**
 * Ręcznie planowane przypomnienie dostaje termin dociągnięty do godzin komunikacji już przy
 * zapisie — użytkownik widzi w UI godzinę, o której SMS naprawdę wyjdzie, a dispatcher
 * przypomnień nigdy nie trafia do kolejki bramki.
 */
class ScheduledSmsReminderWindowTest {

    private val window = SendWindow.DEFAULT
    private val visitRepository: VisitRepository = mockk()
    private val customerRepository: CustomerRepository = mockk()
    private val reminderRepository: ScheduledSmsReminderRepository = mockk()

    private val scheduleHandler = ScheduleVisitSmsReminderHandler(visitRepository, customerRepository, reminderRepository, window)
    private val updateHandler = UpdateSmsReminderHandler(reminderRepository, window)

    private val studioId = StudioId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val customerId = UUID.randomUUID()

    init {
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns mockk<VisitEntity>(relaxed = true).also {
            every { it.customerId } returns customerId
            every { it.appointmentId } returns UUID.randomUUID()
        }
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns mockk<CustomerEntity>(relaxed = true).also {
            every { it.phone } returns "534920205"
        }
        every { reminderRepository.findPendingByVisitId(any(), any()) } returns null
        every { reminderRepository.save(any()) } answers { firstArg() }
    }

    // ── Planowanie ───────────────────────────────────────────────────────────

    @Test
    fun `termin o 9 rano jest przesuwany na poludnie tego samego dnia`() {
        val saved = scheduleHandler.handle(command(scheduledFor = at(9, 0)))
        assertEquals(at(12, 0), saved.scheduledFor)
    }

    @Test
    fun `termin o 20 wieczorem jest przesuwany na poludnie nastepnego dnia`() {
        val saved = scheduleHandler.handle(command(scheduledFor = at(20, 0)))
        assertEquals(at(12, 0, plusDays = 1), saved.scheduledFor)
    }

    @Test
    fun `termin w oknie zostaje bez zmian`() {
        val saved = scheduleHandler.handle(command(scheduledFor = at(15, 45)))
        assertEquals(at(15, 45), saved.scheduledFor)
    }

    @Test
    fun `domyslny termin za 90 dni tez trafia w okno`() {
        val saved = scheduleHandler.handle(command(scheduledFor = null))
        assert(window.contains(saved.scheduledFor)) { "spodziewano się terminu w oknie, było ${saved.scheduledFor}" }
    }

    @Test
    fun `termin z przeszlosci jest odrzucany, a nie przesuwany na jutro`() {
        assertThrows(ValidationException::class.java) {
            scheduleHandler.handle(command(scheduledFor = Instant.now().minusSeconds(3600)))
        }
    }

    // ── Edycja ───────────────────────────────────────────────────────────────

    @Test
    fun `edycja terminu tez dociaga go do okna`() {
        val reminder = pendingReminder()
        every { reminderRepository.findById(reminder.id) } returns reminder

        val updated = updateHandler.handle(
            UpdateSmsReminderCommand(studioId, reminder.id, "Nowa treść", scheduledFor = at(7, 30))
        )

        assertEquals(at(12, 0), updated.scheduledFor)
        assertEquals("Nowa treść", updated.messageContent)
    }

    @Test
    fun `wyslanego przypomnienia nie da sie juz przestawic`() {
        val reminder = pendingReminder().copy(status = ScheduledSmsReminderStatus.SENT)
        every { reminderRepository.findById(reminder.id) } returns reminder

        assertThrows(ValidationException::class.java) {
            updateHandler.handle(UpdateSmsReminderCommand(studioId, reminder.id, "Treść", scheduledFor = at(13, 0)))
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun command(scheduledFor: Instant?) = ScheduleVisitSmsReminderCommand(
        studioId = studioId, visitId = visitId, userId = userId, messageContent = "Czas na kolejny detailing!", scheduledFor = scheduledFor
    )

    private fun pendingReminder() = ScheduledSmsReminder(
        id = UUID.randomUUID(), studioId = studioId.value, visitId = visitId.value, customerId = customerId,
        appointmentId = UUID.randomUUID(), phoneNumber = "+48534920205", messageContent = "Treść",
        scheduledFor = at(13, 0), status = ScheduledSmsReminderStatus.PENDING, sentAt = null,
        externalMessageId = null, errorMessage = null, createdBy = userId.value,
        createdAt = Instant.now(), updatedAt = Instant.now()
    )

    /** Godzina lokalna studia, zawsze w przyszłości względem zegara systemowego handlera. */
    private fun at(hour: Int, minute: Int, plusDays: Long = 0): Instant =
        LocalDateTime.of(LocalDate.now(window.zone).plusYears(1).plusDays(plusDays), LocalTime.of(hour, minute))
            .atZone(window.zone).toInstant()
}
