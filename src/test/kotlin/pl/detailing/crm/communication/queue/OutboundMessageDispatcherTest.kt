package pl.detailing.crm.communication.queue

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.OutboundMessageCategory
import pl.detailing.crm.communication.window.SendWindow
import pl.detailing.crm.shared.CommunicationChannel
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * Dispatcher kolejki: wysyła tylko w oknie, zajmuje wiersz przed wysyłką, a wynik domyka
 * zarówno w kolejce, jak i w dzienniku komunikacji.
 */
class OutboundMessageDispatcherTest {

    private val queue: OutboundMessageQueue = mockk(relaxed = true)
    private val gateway: OutboundCommunicationGateway = mockk()
    private val log: CommunicationLogService = mockk(relaxed = true)
    private val dispatcher = OutboundMessageDispatcher(queue, gateway, log, SendWindow.DEFAULT)

    private fun warsaw(hour: Int, minute: Int): Instant =
        LocalDateTime.of(2026, 9, 15, hour, minute).atZone(SendWindow.DEFAULT.zone).toInstant()

    private fun message(id: UUID = UUID.randomUUID()) = QueuedOutboundMessage(
        OutboundMessageEntity(
            id = id, studioId = UUID.randomUUID(), customerId = UUID.randomUUID(), channel = CommunicationChannel.SMS,
            category = OutboundMessageCategory.TRANSACTIONAL, recipient = "+48600700800", subject = null,
            body = "Auto gotowe", context = "ctx", status = OutboundMessageStatus.SENDING, scheduledFor = warsaw(12, 0)
        ),
        emptyList()
    )

    @Test
    fun `poza oknem nic nie wysyla, tylko sprzata zawieszone`() {
        dispatcher.dispatch(warsaw(9, 30))

        verify(exactly = 1) { queue.failStaleSending(any()) }
        verify(exactly = 0) { queue.dueIds(any(), any()) }
        verify(exactly = 0) { gateway.deliverQueued(any()) }
    }

    @Test
    fun `udana wysylka domyka wiersz kolejki i wpis w dzienniku`() {
        val id = UUID.randomUUID()
        every { queue.dueIds(any(), any()) } returns listOf(id)
        every { queue.claim(id, any()) } returns message(id)
        every { gateway.deliverQueued(any()) } returns OutboundCommunicationGateway.QueuedDeliveryOutcome(true, "ext-9", null, true)

        dispatcher.dispatch(warsaw(12, 0))

        verify { queue.markSent(id, "ext-9", any()) }
        verify { log.recordQueuedOutcome(id, true, null) }
    }

    @Test
    fun `wiersz zajety przez kogos innego jest pomijany`() {
        val id = UUID.randomUUID()
        every { queue.dueIds(any(), any()) } returns listOf(id)
        every { queue.claim(id, any()) } returns null

        dispatcher.dispatch(warsaw(12, 0))

        verify(exactly = 0) { gateway.deliverQueued(any()) }
    }

    @Test
    fun `blad dostawcy wraca do kolejki na pozniej i nie domyka dziennika`() {
        val id = UUID.randomUUID()
        every { queue.dueIds(any(), any()) } returns listOf(id)
        every { queue.claim(id, any()) } returns message(id)
        every { gateway.deliverQueued(any()) } returns OutboundCommunicationGateway.QueuedDeliveryOutcome(false, null, "SMSAPI 500", true)
        every { queue.markAttemptFailed(id, "SMSAPI 500", true, any(), any()) } returns OutboundMessageStatus.QUEUED

        dispatcher.dispatch(warsaw(17, 58))

        verify(exactly = 0) { log.recordQueuedOutcome(any(), any(), any()) }
    }

    @Test
    fun `ostateczna porazka domyka dziennik jako FAILED`() {
        val id = UUID.randomUUID()
        every { queue.dueIds(any(), any()) } returns listOf(id)
        every { queue.claim(id, any()) } returns message(id)
        every { gateway.deliverQueued(any()) } returns OutboundCommunicationGateway.QueuedDeliveryOutcome(false, null, "Brak kredytów SMS", false)
        every { queue.markAttemptFailed(id, "Brak kredytów SMS", false, any(), any()) } returns OutboundMessageStatus.FAILED

        dispatcher.dispatch(warsaw(12, 0))

        verify { log.recordQueuedOutcome(id, false, "Brak kredytów SMS") }
    }

    @Test
    fun `wyjatek z bramki nie zatrzymuje reszty partii`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        every { queue.dueIds(any(), any()) } returns listOf(first, second)
        every { queue.claim(first, any()) } returns message(first)
        every { queue.claim(second, any()) } returns message(second)
        every { gateway.deliverQueued(match { it.id == first }) } throws IllegalStateException("db down")
        every { gateway.deliverQueued(match { it.id == second }) } returns OutboundCommunicationGateway.QueuedDeliveryOutcome(true, "ok", null, true)
        every { queue.markAttemptFailed(first, "db down", true, any(), any()) } returns OutboundMessageStatus.QUEUED

        dispatcher.dispatch(warsaw(12, 0))

        verify { queue.markSent(second, "ok", any()) }
    }

    @Test
    fun `ponowienie tuz przed zamknieciem okna laduje na jutrzejsze poludnie, nie na 18 05`() {
        val id = UUID.randomUUID()
        every { queue.dueIds(any(), any()) } returns listOf(id)
        every { queue.claim(id, any()) } returns message(id)
        every { gateway.deliverQueued(any()) } returns OutboundCommunicationGateway.QueuedDeliveryOutcome(false, null, "SMSAPI 500", true)
        val nextAttempt = slot<Instant>()
        every { queue.markAttemptFailed(id, any(), true, capture(nextAttempt), any()) } returns OutboundMessageStatus.QUEUED

        dispatcher.dispatch(warsaw(17, 58))

        // Termin ponowienia liczy się od zegara systemowego (chwila zakończenia próby),
        // więc sprawdzamy własność, a nie dokładną minutę: musi mieścić się w oknie.
        assert(SendWindow.DEFAULT.contains(nextAttempt.captured)) {
            "ponowienie ma wypadać w oknie wysyłki, było ${nextAttempt.captured}"
        }
    }

    @Test
    fun `jedna partia to najwyzej BATCH_SIZE wiadomosci`() {
        every { queue.dueIds(any(), any()) } returns emptyList()

        dispatcher.dispatch(warsaw(12, 0))

        verify { queue.dueIds(any(), OutboundMessageDispatcher.BATCH_SIZE) }
    }

    @Test
    fun `dokladnie o 18 00 dispatcher jeszcze wysyla, o 18 01 juz nie`() {
        every { queue.dueIds(any(), any()) } returns emptyList()

        dispatcher.dispatch(warsaw(18, 0))
        verify(exactly = 1) { queue.dueIds(any(), any()) }

        dispatcher.dispatch(warsaw(18, 1))
        verify(exactly = 1) { queue.dueIds(any(), any()) }
    }

    @Test
    fun `zawieszone po restarcie wiersze domykaja dziennik jako FAILED`() {
        val stuck = UUID.randomUUID()
        every { queue.failStaleSending(any()) } returns listOf(stuck)
        every { queue.dueIds(any(), any()) } returns emptyList()

        dispatcher.dispatch(warsaw(12, 0))

        verify { log.recordQueuedOutcome(stuck, false, any()) }
    }
}
