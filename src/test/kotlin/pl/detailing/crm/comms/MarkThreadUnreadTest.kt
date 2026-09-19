package pl.detailing.crm.comms

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
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
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * „Oznacz jako nieprzeczytaną" klikane na WĄTKU, z listy rozmów.
 *
 * Reguła, o którą tu chodzi: cofamy dokładnie JEDNĄ wiadomość - najnowszą
 * przychodzącą - a nie całą rozmowę. Wątek z piętnastoma wiadomościami klienta ma
 * po takim kliknięciu pokazać licznik „1": tyle użytkownik zostawia sobie do
 * przeczytania, a cofnięcie wszystkich kazałoby mu je potem odklikiwać.
 */
class MarkThreadUnreadTest {

    private val messageRepository: CommMessageRepository = mockk(relaxed = true)
    private val threadRepository: CommThreadRepository = mockk(relaxed = true)
    private val outboxRepository: CommOutboxRepository = mockk(relaxed = true)
    private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)

    private val service = CommsReadService(messageRepository, threadRepository, outboxRepository, eventPublisher)

    private val studioId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()

    private fun message(
        id: UUID = UUID.randomUUID(),
        direction: CommDirection = CommDirection.INBOUND,
        isRead: Boolean = true
    ) = CommMessageEntity(
        id = id,
        studioId = studioId,
        accountId = accountId,
        threadId = threadId,
        direction = direction,
        folderKind = if (direction == CommDirection.INBOUND) CommFolderKind.INBOX else CommFolderKind.SENT,
        messageIdHdr = "$id@example.com",
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

    /** Ustawia wątek, w którym najnowszą wiadomością przychodzącą jest [newest]. */
    private fun newestInbound(newest: CommMessageEntity?) {
        // Mock „relaxed" oddaje z save() gołego Object-a, a serwis zapisuje encje
        // i czyta je dalej - bez tego test wywraca się na rzutowaniu, nie na regule.
        every { messageRepository.save(any()) } answers { firstArg() }
        every { threadRepository.save(any()) } answers { firstArg() }
        every { outboxRepository.save(any()) } answers { firstArg() }
        every {
            messageRepository.findFirstByStudioIdAndThreadIdAndDirectionOrderBySentAtDesc(
                studioId, threadId, CommDirection.INBOUND
            )
        } returns newest
        if (newest != null) {
            every { messageRepository.findByIdAndStudioId(newest.id, studioId) } returns newest
        }
    }

    @Test
    fun `cofa najnowsza wiadomosc przychodzaca i oddaje jej identyfikator`() {
        val newest = message()
        newestInbound(newest)
        every { threadRepository.findById(threadId) } returns Optional.of(thread(unread = 0))

        val result = service.markThreadUnreadFromCrm(studioId, threadId)

        assertEquals(newest.id, result)
        assertFalse(newest.isRead)
        assertNull(newest.readAt)
        assertNull(newest.readSource)
    }

    @Test
    fun `licznik watku rosnie o jeden, a nie o liczbe wiadomosci w rozmowie`() {
        newestInbound(message())
        val stored = thread(unread = 0)
        every { threadRepository.findById(threadId) } returns Optional.of(stored)
        val saved = slot<CommThreadEntity>()
        every { threadRepository.save(capture(saved)) } answers { saved.captured }

        service.markThreadUnreadFromCrm(studioId, threadId)

        assertEquals(1, saved.captured.unreadCount)
    }

    @Test
    fun `serwer dostaje MARK_UNSEEN dla tej jednej wiadomosci`() {
        val newest = message()
        newestInbound(newest)
        every { threadRepository.findById(threadId) } returns Optional.of(thread(unread = 0))
        val command = slot<CommOutboxEntity>()
        every { outboxRepository.save(capture(command)) } answers { command.captured }

        service.markThreadUnreadFromCrm(studioId, threadId)

        assertEquals(CommOutboxType.MARK_UNSEEN, command.captured.commandType)
        assertEquals(newest.id, command.captured.messageId)
    }

    @Test
    fun `watek bez wiadomosci przychodzacych nie ma czego cofac`() {
        newestInbound(null)

        assertNull(service.markThreadUnreadFromCrm(studioId, threadId))
        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { threadRepository.save(any()) }
    }

    @Test
    fun `drugie klikniecie niczego nie podbija - najnowsza juz czeka nieprzeczytana`() {
        newestInbound(message(isRead = false))

        assertNull(service.markThreadUnreadFromCrm(studioId, threadId))
        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { threadRepository.save(any()) }
    }
}
