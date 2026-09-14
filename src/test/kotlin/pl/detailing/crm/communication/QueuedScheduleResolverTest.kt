package pl.detailing.crm.communication

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.queue.OutboundMessageEntity
import pl.detailing.crm.communication.queue.OutboundMessageJpaRepository
import pl.detailing.crm.communication.queue.OutboundMessageStatus
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import java.time.Instant
import java.util.UUID

/** „W kolejce, wyjdzie o …" — termin bierze się z wiersza kolejki, tylko dla wpisów QUEUED. */
class QueuedScheduleResolverTest {

    private val queue: OutboundMessageJpaRepository = mockk()
    private val resolver = QueuedScheduleResolver(queue)

    private fun entry(status: CommunicationStatus, queuedMessageId: UUID? = null) = CommunicationLogEntity(
        id = UUID.randomUUID(), studioId = UUID.randomUUID(), customerId = UUID.randomUUID(), visitId = null, appointmentId = null,
        channel = CommunicationChannel.SMS, messageType = CommunicationMessageType.MANUAL_SMS, recipientAddress = "+48600700800",
        subject = null, bodyContent = "x", status = status, errorMessage = null, sentAt = Instant.now(), queuedMessageId = queuedMessageId
    )

    private fun queued(id: UUID, scheduledFor: Instant) = OutboundMessageEntity(
        id = id, studioId = UUID.randomUUID(), customerId = null, channel = CommunicationChannel.SMS,
        category = OutboundMessageCategory.TRANSACTIONAL, recipient = "+48600700800", subject = null, body = "x",
        context = "ctx", status = OutboundMessageStatus.QUEUED, scheduledFor = scheduledFor
    )

    @Test
    fun `wpis QUEUED dostaje termin z kolejki, pozostale nie`() {
        val queuedId = UUID.randomUUID()
        val at = Instant.parse("2026-09-16T10:00:00Z")
        val queuedEntry = entry(CommunicationStatus.QUEUED, queuedId)
        val sentEntry = entry(CommunicationStatus.SENT, UUID.randomUUID())
        every { queue.findAllById(listOf(queuedId)) } returns listOf(queued(queuedId, at))

        val result = resolver.resolve(listOf(queuedEntry, sentEntry))

        assertEquals(mapOf(queuedEntry.id to at), result)
    }

    @Test
    fun `bez wpisow QUEUED nie ma zapytania do kolejki`() {
        assertEquals(emptyMap<UUID, Instant>(), resolver.resolve(listOf(entry(CommunicationStatus.SENT), entry(CommunicationStatus.FAILED))))
        verify(exactly = 0) { queue.findAllById(any()) }
    }

    @Test
    fun `wpis QUEUED bez wiersza kolejki zostaje bez terminu`() {
        val queuedId = UUID.randomUUID()
        every { queue.findAllById(listOf(queuedId)) } returns emptyList()

        assertEquals(emptyMap<UUID, Instant>(), resolver.resolve(listOf(entry(CommunicationStatus.QUEUED, queuedId))))
    }
}
