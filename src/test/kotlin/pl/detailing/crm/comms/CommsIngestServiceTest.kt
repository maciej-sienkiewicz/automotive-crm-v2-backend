package pl.detailing.crm.comms

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.domain.ParsedEmail
import pl.detailing.crm.comms.engine.CommsIngestService
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import java.time.Instant
import java.util.Optional
import java.util.UUID

class CommsIngestServiceTest {

    private val threadRepository = mockk<CommThreadRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val messageRepository = mockk<CommMessageRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val attachmentRepository = mockk<CommAttachmentRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val eventPublisher = mockk<ApplicationEventPublisher> {
        // publishEvent ma przeciazenia (Object) i (ApplicationEvent) — stub musi objac oba,
        // bo zdarzenia domenowe nie dziedzicza po ApplicationEvent
        every { publishEvent(any<Any>()) } just Runs
        every { publishEvent(any<org.springframework.context.ApplicationEvent>()) } just Runs
    }
    private val service = CommsIngestService(
        threadRepository, messageRepository, attachmentRepository,
        EmailHtmlSanitizer(), EmailTextCleaner(), eventPublisher
    )

    private val account = MailAccountEntity(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        emailAddress = "studio@example.pl",
        providerType = MailProviderType.IMAP_SMTP,
        authType = MailAuthType.PASSWORD,
        encryptedPassword = "ENC:x",
        imapHost = "imap.example.pl",
        imapPort = 993,
        smtpHost = "smtp.example.pl",
        smtpPort = 587,
        status = MailAccountStatus.ACTIVE,
        lastError = null,
        lastSyncAt = null,
        inboxUidValidity = null,
        inboxLastUid = null,
        sentUidValidity = null,
        sentLastUid = null
    )

    private fun messageEntity(threadId: UUID, messageIdHdr: String) = CommMessageEntity(
        id = UUID.randomUUID(),
        studioId = account.studioId,
        accountId = account.id,
        threadId = threadId,
        direction = CommDirection.OUTBOUND,
        folderKind = CommFolderKind.SENT,
        messageIdHdr = messageIdHdr,
        inReplyTo = null,
        referencesIds = null,
        fromEmail = account.emailAddress,
        fromName = null,
        toEmails = "klient@example.com",
        ccEmails = null,
        subject = "Wycena BMW",
        sentAt = Instant.now().minusSeconds(7200),
        bodyHtmlSafe = null,
        bodyText = null,
        bodyTextClean = null,
        hasAttachments = false,
        imapUid = null,
        imapUidValidity = null,
        isRead = true,
        readSource = null,
        readAt = null,
        sendStatus = pl.detailing.crm.comms.domain.CommSendStatus.SENDING
    )

    private fun parsed(
        messageId: String? = "msg-1@example.com",
        references: List<String> = emptyList(),
        seen: Boolean = false,
        from: String = "klient@example.com"
    ) = ParsedEmail(
        messageId = messageId,
        inReplyTo = null,
        references = references,
        fromEmail = from,
        fromName = "Jan Klient",
        toEmails = listOf("studio@example.pl"),
        ccEmails = emptyList(),
        subject = "Wycena BMW",
        sentAt = Instant.now(),
        bodyHtml = "<p>Dzień dobry</p>",
        bodyText = null,
        attachments = emptyList(),
        imapUid = 42L,
        seen = seen
    )

    @Test
    fun `new inbound message creates thread and counts unread`() {
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every { messageRepository.findByAccountIdAndMessageIdHdrIn(any(), any()) } returns emptyList()
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()

        val thread = slot<CommThreadEntity>()
        every { threadRepository.save(capture(thread)) } answers { firstArg() }

        val stored = service.ingest(account, CommFolderKind.INBOX, parsed(), uidValidity = 7L)

        assertTrue(stored)
        assertEquals("klient@example.com", thread.captured.participantEmail)
        assertEquals(1, thread.captured.unreadCount)
        assertEquals(1, thread.captured.messageCount)
        assertEquals(CommDirection.INBOUND, thread.captured.lastDirection)
        assertEquals(1, thread.captured.inboundCount)
        assertEquals(0, thread.captured.outboundCount)
    }

    @Test
    fun `message sent from scratch creates a thread that belongs to Sent only`() {
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every { messageRepository.findByAccountIdAndMessageIdHdrIn(any(), any()) } returns emptyList()
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()

        val thread = slot<CommThreadEntity>()
        every { threadRepository.save(capture(thread)) } answers { firstArg() }

        service.ingest(
            account, CommFolderKind.SENT,
            parsed(from = account.emailAddress).copy(toEmails = listOf("klient@example.com")),
            uidValidity = null
        )

        // Bez ani jednej wiadomości od klienta wątek nie jest „odebraną" korespondencją.
        assertEquals(0, thread.captured.inboundCount)
        assertEquals(1, thread.captured.outboundCount)
        assertEquals(0, thread.captured.unreadCount)
        assertEquals("klient@example.com", thread.captured.participantEmail)
    }

    @Test
    fun `duplicate message adopts imap identity instead of duplicating`() {
        val existing = messageEntity(UUID.randomUUID(), "msg-1@example.com")
        every { messageRepository.findByAccountIdAndMessageIdHdr(account.id, "msg-1@example.com") } returns existing

        val stored = service.ingest(account, CommFolderKind.SENT, parsed(), uidValidity = 7L)

        assertFalse(stored)
        assertEquals(42L, existing.imapUid)
        assertEquals(7L, existing.imapUidValidity)
    }

    @Test
    fun `reply joins existing thread via references`() {
        val threadId = UUID.randomUUID()
        val existingThread = CommThreadEntity(
            id = threadId,
            studioId = account.studioId,
            accountId = account.id,
            subjectNorm = "wycena bmw",
            subject = "Wycena BMW",
            participantEmail = "klient@example.com",
            participantName = null,
            lastMessageAt = Instant.now().minusSeconds(3600),
            lastDirection = CommDirection.OUTBOUND,
            lastSnippet = null,
            leadId = null,
            labelId = null,
            messageCount = 1
        )
        val relative = messageEntity(threadId, "root@example.com")
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every {
            messageRepository.findByAccountIdAndMessageIdHdrIn(account.id, listOf("root@example.com"))
        } returns listOf(relative)
        every { threadRepository.findById(threadId) } returns Optional.of(existingThread)

        val message = slot<CommMessageEntity>()
        every { messageRepository.save(capture(message)) } answers { firstArg() }

        service.ingest(
            account, CommFolderKind.INBOX,
            parsed(messageId = "msg-2@example.com", references = listOf("root@example.com")),
            uidValidity = 7L
        )

        assertEquals(threadId, message.captured.threadId)
        assertEquals(2, existingThread.messageCount)
        // Odpowiedź klienta wciąga wątek z Wysłanych z powrotem do Odebranych.
        assertEquals(1, existingThread.inboundCount)
    }

    /**
     * Powiadomienie „Masz nową wiadomość" po kliknięciu „Wyślij" to komunikat
     * nieprawdziwy: autor wiadomości wie, że ją wysłał. Kopia własnej wysyłki idzie
     * tą samą ścieżką importu co poczta przychodząca, więc flaga musi rozróżniać
     * kierunek — samo „nie backfill" nie wystarcza.
     */
    @Test
    fun `outbound copy refreshes the thread without announcing a new message`() {
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every { messageRepository.findByAccountIdAndMessageIdHdrIn(any(), any()) } returns emptyList()
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()

        val events = mutableListOf<Any>()
        every { eventPublisher.publishEvent(capture(events)) } just Runs

        service.ingest(
            account, CommFolderKind.SENT,
            parsed(from = account.emailAddress).copy(toEmails = listOf("klient@example.com")),
            uidValidity = null
        )

        val changed = events.filterIsInstance<CommThreadChangedEvent>().single()
        assertFalse(changed.newMessage)
    }

    @Test
    fun `inbound message announces a new message`() {
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every { messageRepository.findByAccountIdAndMessageIdHdrIn(any(), any()) } returns emptyList()
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()

        val events = mutableListOf<Any>()
        every { eventPublisher.publishEvent(capture(events)) } just Runs

        service.ingest(account, CommFolderKind.INBOX, parsed(), uidValidity = 7L)

        val changed = events.filterIsInstance<CommThreadChangedEvent>().single()
        assertTrue(changed.newMessage)
    }

    /** Pierwszy import skrzynki: setki historycznych maili to nie setki powiadomień. */
    @Test
    fun `backfilled inbound message stays silent`() {
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every { messageRepository.findByAccountIdAndMessageIdHdrIn(any(), any()) } returns emptyList()
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()

        val events = mutableListOf<Any>()
        every { eventPublisher.publishEvent(capture(events)) } just Runs

        service.ingest(account, CommFolderKind.INBOX, parsed(), uidValidity = 7L, backfill = true)

        val changed = events.filterIsInstance<CommThreadChangedEvent>().single()
        assertFalse(changed.newMessage)
    }

    @Test
    fun `server-seen inbound message lands as read with external source`() {
        every { messageRepository.findByAccountIdAndMessageIdHdr(any(), any()) } returns null
        every { messageRepository.findByAccountIdAndMessageIdHdrIn(any(), any()) } returns emptyList()
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()

        val message = slot<CommMessageEntity>()
        every { messageRepository.save(capture(message)) } answers { firstArg() }

        service.ingest(account, CommFolderKind.INBOX, parsed(seen = true), uidValidity = 7L)

        assertTrue(message.captured.isRead)
        assertEquals(pl.detailing.crm.comms.domain.CommReadSource.EXTERNAL, message.captured.readSource)
    }
}
