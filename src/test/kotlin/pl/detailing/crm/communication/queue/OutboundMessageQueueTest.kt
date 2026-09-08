package pl.detailing.crm.communication.queue

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.OutboundMessageCategory
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.shared.CommunicationChannel
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Stan wiersza kolejki: zajęcie jest atomowe, ponowienia mają limit, brak kredytów
 * kończy od razu, a zawieszone po restarcie wiersze nie są ponawiane (dubel SMS-a
 * kosztuje kredyt i irytuje klienta).
 */
class OutboundMessageQueueTest {

    private val repository: OutboundMessageJpaRepository = mockk(relaxed = true)
    private val attachments: OutboundMessageAttachmentJpaRepository = mockk(relaxed = true)
    private val queue = OutboundMessageQueue(repository, attachments, SimpleMeterRegistry())

    init {
        // Relaxed mock generycznego save() zwraca Object, a Kotlin rzutuje wynik — stub musi oddać encję.
        every { repository.save(any()) } answers { firstArg() }
        every { attachments.save(any()) } answers { firstArg() }
    }

    private val now: Instant = Instant.parse("2026-09-15T10:00:00Z")

    private fun entity(attempts: Int = 0, status: OutboundMessageStatus = OutboundMessageStatus.SENDING, updatedAt: Instant = now) =
        OutboundMessageEntity(
            id = UUID.randomUUID(), studioId = UUID.randomUUID(), customerId = null, channel = CommunicationChannel.SMS,
            category = OutboundMessageCategory.TRANSACTIONAL, recipient = "+48600700800", subject = null, body = "Treść",
            context = "ctx", status = status, scheduledFor = now, attempts = attempts, updatedAt = updatedAt
        )

    // ── enqueue ──────────────────────────────────────────────────────────────

    @Test
    fun `odlozona wiadomosc jest QUEUED z terminem i zalacznikami w osobnej tabeli`() {
        val pdf = EmailAttachment("z.pdf", byteArrayOf(9), "application/pdf")
        val draft = OutboundMessageDraft(
            UUID.randomUUID(), UUID.randomUUID(), CommunicationChannel.EMAIL, OutboundMessageCategory.TRANSACTIONAL,
            "jan@klient.pl", "Temat", "Treść", "ctx", listOf(pdf)
        )

        val saved = queue.enqueue(draft, scheduledFor = now.plus(Duration.ofHours(2)), now = now)

        assertEquals(OutboundMessageStatus.QUEUED, saved.status)
        assertEquals(now.plus(Duration.ofHours(2)), saved.scheduledFor)
        assertEquals(0, saved.attempts)
        val stored = slot<OutboundMessageAttachmentEntity>()
        verify(exactly = 1) { attachments.save(capture(stored)) }
        assertEquals(saved.id, stored.captured.messageId)
        assertEquals("z.pdf", stored.captured.fileName)
    }

    // ── claim ────────────────────────────────────────────────────────────────

    @Test
    fun `zajecie wiersza udaje sie tylko raz - drugi chetny dostaje null`() {
        val e = entity(status = OutboundMessageStatus.QUEUED)
        every { repository.transition(e.id, OutboundMessageStatus.QUEUED, OutboundMessageStatus.SENDING, any()) } returnsMany listOf(1, 0)
        every { repository.findById(e.id) } returns Optional.of(e)

        assertNotNull(queue.claim(e.id, now))
        assertNull(queue.claim(e.id, now))
    }

    @Test
    fun `zajety wiersz wraca razem z zalacznikami gotowymi do wysylki`() {
        val e = entity(status = OutboundMessageStatus.QUEUED)
        every { repository.transition(any(), any(), any(), any()) } returns 1
        every { repository.findById(e.id) } returns Optional.of(e)
        every { attachments.findAllByMessageId(e.id) } returns listOf(
            OutboundMessageAttachmentEntity(UUID.randomUUID(), e.id, "z.pdf", "application/pdf", byteArrayOf(1, 2))
        )

        val claimed = queue.claim(e.id, now)!!

        assertEquals(1, claimed.attachments.size)
        assertEquals("z.pdf", claimed.attachments.single().fileName)
        assertEquals("application/pdf", claimed.attachments.single().contentType)
    }

    // ── wynik próby ──────────────────────────────────────────────────────────

    @Test
    fun `sukces oznacza SENT, zapisuje id u dostawcy i kasuje zalaczniki`() {
        val e = entity()
        every { repository.findById(e.id) } returns Optional.of(e)

        queue.markSent(e.id, "ext-7", now)

        assertEquals(OutboundMessageStatus.SENT, e.status)
        assertEquals("ext-7", e.externalMessageId)
        assertEquals(now, e.sentAt)
        assertEquals(1, e.attempts)
        verify { attachments.deleteAll(any<Iterable<OutboundMessageAttachmentEntity>>()) }
    }

    @Test
    fun `blad dostawcy wraca do QUEUED z nowym terminem`() {
        val e = entity(attempts = 0)
        every { repository.findById(e.id) } returns Optional.of(e)
        val retryAt = now.plus(Duration.ofMinutes(5))

        val status = queue.markAttemptFailed(e.id, "SMSAPI 500", retryable = true, nextAttemptAt = retryAt, now = now)

        assertEquals(OutboundMessageStatus.QUEUED, status)
        assertEquals(retryAt, e.scheduledFor)
        assertEquals(1, e.attempts)
        assertEquals("SMSAPI 500", e.lastError)
    }

    @Test
    fun `trzecia nieudana proba konczy wiadomosc jako FAILED`() {
        val e = entity(attempts = OutboundMessageQueue.MAX_ATTEMPTS - 1)
        every { repository.findById(e.id) } returns Optional.of(e)

        val status = queue.markAttemptFailed(e.id, "SMSAPI 500", retryable = true, nextAttemptAt = now, now = now)

        assertEquals(OutboundMessageStatus.FAILED, status)
        assertEquals(OutboundMessageQueue.MAX_ATTEMPTS, e.attempts)
        verify { attachments.deleteAll(any<Iterable<OutboundMessageAttachmentEntity>>()) }
    }

    @Test
    fun `brak kredytow konczy od razu, bez ponowien`() {
        val e = entity(attempts = 0)
        every { repository.findById(e.id) } returns Optional.of(e)

        val status = queue.markAttemptFailed(e.id, "Brak kredytów SMS", retryable = false, nextAttemptAt = now, now = now)

        assertEquals(OutboundMessageStatus.FAILED, status)
        assertEquals("Brak kredytów SMS", e.lastError)
    }

    @Test
    fun `wynik dla wiersza, ktorego juz nie ma, nie wywraca dispatchera`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns Optional.empty()

        assertNull(queue.markAttemptFailed(id, "x", retryable = true, nextAttemptAt = now, now = now))
        queue.markSent(id, "ext", now)
    }

    // ── sprzątanie po restarcie ──────────────────────────────────────────────

    @Test
    fun `wiersz zawieszony w SENDING ponad kwadrans zostaje FAILED, nie ponowiony`() {
        val stuck = entity(updatedAt = now.minus(Duration.ofMinutes(20)))
        every {
            repository.findByStatusUpdatedBefore(OutboundMessageStatus.SENDING, now.minus(OutboundMessageQueue.STALE_SENDING_AFTER))
        } returns listOf(stuck)

        val ids = queue.failStaleSending(now)

        assertEquals(listOf(stuck.id), ids)
        assertEquals(OutboundMessageStatus.FAILED, stuck.status)
        assertEquals("Wysyłka przerwana (restart aplikacji w trakcie wysyłki)", stuck.lastError)
    }

    @Test
    fun `wiersz w trakcie wysylki sprzed chwili nie jest ruszany`() {
        every { repository.findByStatusUpdatedBefore(any(), any()) } returns emptyList()

        assertEquals(emptyList<UUID>(), queue.failStaleSending(now))
        verify(exactly = 0) { repository.save(any()) }
    }
}
