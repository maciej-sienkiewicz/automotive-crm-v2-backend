package pl.detailing.crm.comms

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommMessageReadEvent
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.domain.CommReadSource
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.engine.CommsReadService
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommOutboxEntity
import pl.detailing.crm.comms.infrastructure.CommOutboxRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * „Oznacz jako nieprzeczytaną" łamie jedyny raz monotoniczność modelu czytania,
 * więc musi zrobić trzy rzeczy naraz: odwrócić stan lokalnie, podbić licznik wątku
 * ORAZ zakolejkować MARK_UNSEEN — bez tego ostatniego reconcile cofnąłby oznaczenie.
 */
class CommsReadServiceUnreadTest {

    private val messageRepository: CommMessageRepository = mockk(relaxed = true)
    private val threadRepository: CommThreadRepository = mockk(relaxed = true)
    private val outboxRepository: CommOutboxRepository = mockk(relaxed = true)
    private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)

    private val service = CommsReadService(messageRepository, threadRepository, outboxRepository, eventPublisher)

    private val studioId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()
    private val messageId = UUID.randomUUID()

    private fun message(direction: CommDirection, isRead: Boolean) = CommMessageEntity(
        id = messageId,
        studioId = studioId,
        accountId = accountId,
        threadId = threadId,
        direction = direction,
        folderKind = if (direction == CommDirection.INBOUND) CommFolderKind.INBOX else CommFolderKind.SENT,
        messageIdHdr = "msg@example.com",
        inReplyTo = null,
        referencesIds = null,
        fromEmail = "klient@example.com",
        fromName = "Klient",
        toEmails = "biuro@example.com",
        ccEmails = null,
        subject = "Pytanie",
        sentAt = Instant.parse("2026-09-17T09:00:00Z"),
        bodyHtmlSafe = null,
        bodyText = "treść",
        bodyTextClean = "treść",
        imapUid = 42L,
        imapUidValidity = 1L,
        readSource = if (isRead) CommReadSource.CRM else null,
        readAt = if (isRead) Instant.now() else null,
        sendStatus = CommSendStatus.RECEIVED
    ).apply { this.isRead = isRead }

    private fun thread(unread: Int) = CommThreadEntity(
        id = threadId,
        studioId = studioId,
        accountId = accountId,
        subjectNorm = "pytanie",
        subject = "Pytanie",
        participantEmail = "klient@example.com",
        participantName = "Klient",
        lastMessageAt = Instant.parse("2026-09-17T09:00:00Z"),
        lastDirection = CommDirection.INBOUND,
        lastSnippet = null,
        leadId = null,
        labelId = null
    ).apply { unreadCount = unread }

    @Test
    fun `oznaczenie przeczytanej wiadomosci przychodzacej jako nieprzeczytanej odwraca stan, podbija licznik i kolejkuje MARK_UNSEEN`() {
        val msg = message(CommDirection.INBOUND, isRead = true)
        val thr = thread(unread = 0)
        every { messageRepository.findByIdAndStudioId(messageId, studioId) } returns msg
        every { threadRepository.findById(threadId) } returns Optional.of(thr)
        // JpaRepository.save(S) w relaxed mocku zwraca goły Object, a Kotlin wstawia
        // checkcast do S — bez tych stubów wywala ClassCastException, choć serwis
        // zwrotu i tak nie używa.
        every { messageRepository.save(any<CommMessageEntity>()) } answers { firstArg() }
        every { threadRepository.save(any<CommThreadEntity>()) } answers { firstArg() }

        val outboxSlot = slot<CommOutboxEntity>()
        every { outboxRepository.save(capture(outboxSlot)) } answers { firstArg() }

        service.markUnreadFromCrm(studioId, messageId)

        assertFalse(msg.isRead)
        assertNull(msg.readSource)
        assertNull(msg.readAt)
        assertEquals(1, thr.unreadCount)
        verify(exactly = 1) { messageRepository.save(msg) }
        verify(exactly = 1) { threadRepository.save(thr) }
        assertEquals(CommOutboxType.MARK_UNSEEN, outboxSlot.captured.commandType)
        assertEquals(messageId, outboxSlot.captured.messageId)
        // publishEvent(Any) i publishEvent(ApplicationEvent) to przeciążenia — ofType
        // celuje w konkretny typ bez potykania się o rozstrzyganie przeciążeń.
        verify(exactly = 1) { eventPublisher.publishEvent(ofType<CommMessageReadEvent>()) }
    }

    @Test
    fun `juz nieprzeczytana wiadomosc to no-op - bez zapisu, licznika i outboxa`() {
        val msg = message(CommDirection.INBOUND, isRead = false)
        every { messageRepository.findByIdAndStudioId(messageId, studioId) } returns msg

        service.markUnreadFromCrm(studioId, messageId)

        verify(exactly = 0) { messageRepository.save(any()) }
        verify(exactly = 0) { threadRepository.save(any()) }
        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<CommMessageReadEvent>()) }
    }

    @Test
    fun `wiadomosci wychodzacej nie da sie oznaczyc jako nieprzeczytanej`() {
        val msg = message(CommDirection.OUTBOUND, isRead = true)
        every { messageRepository.findByIdAndStudioId(messageId, studioId) } returns msg

        assertThrows<ValidationException> { service.markUnreadFromCrm(studioId, messageId) }
        verify(exactly = 0) { outboxRepository.save(any()) }
    }
}
