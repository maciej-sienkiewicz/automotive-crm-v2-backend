package pl.detailing.crm.mailbox

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import pl.detailing.crm.mailbox.domain.EmailCleaningService
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.MailSendStatus
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import pl.detailing.crm.mailbox.domain.SubjectNormalizer
import pl.detailing.crm.mailbox.domain.ThreadingService
import pl.detailing.crm.mailbox.infrastructure.MailAutodiscoverService
import pl.detailing.crm.mailbox.infrastructure.MailMessageEntity
import pl.detailing.crm.mailbox.infrastructure.MailMessageRepository
import pl.detailing.crm.mailbox.infrastructure.MailThreadEntity
import pl.detailing.crm.mailbox.infrastructure.MailThreadRepository
import pl.detailing.crm.mailbox.infrastructure.MailboxEncryptionService
import pl.detailing.crm.mailbox.domain.MailProviderType
import java.time.Instant
import java.util.UUID

class SubjectNormalizerTest {

    @Test
    fun `strips repeated polish and english reply prefixes`() {
        assertEquals("wycena bmw x5", SubjectNormalizer.normalize("Re: Odp: FW: Wycena BMW X5"))
        assertEquals("wycena bmw x5", SubjectNormalizer.normalize("ODP:  wycena   BMW X5 "))
        assertEquals("wycena bmw x5", SubjectNormalizer.normalize("PD: Re[2]: Wycena BMW X5"))
    }

    @Test
    fun `returns null when nothing meaningful remains`() {
        assertNull(SubjectNormalizer.normalize(null))
        assertNull(SubjectNormalizer.normalize("   "))
        assertNull(SubjectNormalizer.normalize("Re:"))
    }
}

class MessageIdParsingTest {

    @Test
    fun `normalizes angle brackets and blank values`() {
        assertEquals("abc@mail.com", ThreadingService.normalizeMessageId(" <abc@mail.com> "))
        assertEquals("abc@mail.com", ThreadingService.normalizeMessageId("abc@mail.com"))
        assertNull(ThreadingService.normalizeMessageId("<>"))
        assertNull(ThreadingService.normalizeMessageId(null))
    }

    @Test
    fun `parses references preserving order`() {
        assertEquals(
            listOf("a@x.pl", "b@y.pl", "c@z.pl"),
            ThreadingService.parseReferences("<a@x.pl>\r\n <b@y.pl> <c@z.pl>")
        )
        assertTrue(ThreadingService.parseReferences(null).isEmpty())
    }

    @Test
    fun `synthetic id is stable for the same envelope`() {
        val at = Instant.parse("2026-08-17T10:00:00Z")
        assertEquals(
            ThreadingService.syntheticMessageId("jan@wp.pl", at, "Wycena"),
            ThreadingService.syntheticMessageId("jan@wp.pl", at, "Wycena")
        )
    }
}

class ThreadingServiceTest {

    private val threadRepository = mockk<MailThreadRepository>()
    private val messageRepository = mockk<MailMessageRepository>()
    private val service = ThreadingService(threadRepository, messageRepository)

    private val studioId = UUID.randomUUID()

    @Test
    fun `in-reply-to wins over everything else`() {
        val existing = thread()
        every { messageRepository.findByStudioIdAndMessageId(studioId, "parent@x.pl") } returns
            message(threadId = existing.id, messageId = "parent@x.pl")
        every { threadRepository.findByIdAndStudioId(existing.id, studioId) } returns existing

        val resolved = service.resolveThread(
            studioId = studioId,
            mailAccountId = null,
            inReplyTo = "parent@x.pl",
            references = emptyList(),
            externalThreadKey = null,
            participantEmail = "jan@wp.pl",
            subject = "Re: Wycena",
            sentAt = Instant.now()
        )

        assertSame(existing, resolved)
    }

    @Test
    fun `falls back to references newest first when in-reply-to is missing`() {
        val existing = thread()
        every { messageRepository.findByStudioIdAndMessageId(studioId, "old@x.pl") } returns null
        every { messageRepository.findByStudioIdAndMessageId(studioId, "recent@x.pl") } returns
            message(threadId = existing.id, messageId = "recent@x.pl")
        every { threadRepository.findByIdAndStudioId(existing.id, studioId) } returns existing

        val resolved = service.resolveThread(
            studioId = studioId,
            mailAccountId = null,
            inReplyTo = null,
            references = listOf("old@x.pl", "recent@x.pl"),
            externalThreadKey = null,
            participantEmail = "jan@wp.pl",
            subject = "Re: Wycena",
            sentAt = Instant.now()
        )

        assertSame(existing, resolved)
    }

    @Test
    fun `creates a new thread when no header and no subject match exist`() {
        every { messageRepository.findByStudioIdAndMessageId(any(), any()) } returns null
        every { threadRepository.findRecentBySubjectAndParticipant(any(), any(), any(), any()) } returns emptyList()
        every { threadRepository.save(any()) } answers { firstArg() }

        val resolved = service.resolveThread(
            studioId = studioId,
            mailAccountId = null,
            inReplyTo = null,
            references = emptyList(),
            externalThreadKey = null,
            participantEmail = "nowy@wp.pl",
            subject = "Zapytanie o ceramikę",
            sentAt = Instant.now()
        )

        assertEquals(MailThreadClassification.PENDING, resolved.classification)
        assertEquals("zapytanie o ceramikę", resolved.subjectNorm)
        assertNull(resolved.leadId)
    }

    @Test
    fun `touch does not rewind the thread cursor on out-of-order delivery`() {
        val newest = Instant.parse("2026-08-17T12:00:00Z")
        val existing = thread().apply {
            lastMessageAt = newest
            lastDirection = MailDirection.OUTBOUND
        }
        every { threadRepository.save(any()) } answers { firstArg() }

        service.touch(existing, Instant.parse("2026-08-17T09:00:00Z"), MailDirection.INBOUND)

        assertEquals(newest, existing.lastMessageAt)
        assertEquals(MailDirection.OUTBOUND, existing.lastDirection)
    }

    private fun thread() = MailThreadEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        mailAccountId = null,
        leadId = null,
        subjectNorm = "wycena",
        externalThreadKey = null,
        classification = MailThreadClassification.PENDING,
        lastMessageAt = Instant.now(),
        lastDirection = null
    )

    private fun message(threadId: UUID, messageId: String) = MailMessageEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        threadId = threadId,
        mailAccountId = null,
        direction = MailDirection.INBOUND,
        messageId = messageId,
        inReplyTo = null,
        referencesIds = null,
        fromEmail = "jan@wp.pl",
        fromName = "Jan",
        toEmails = null,
        subject = "Wycena",
        sentAt = Instant.now(),
        bodyHtml = null,
        bodyTextClean = "treść",
        hasAttachments = false,
        providerUid = null,
        sendStatus = MailSendStatus.RECEIVED
    )
}

class EmailCleaningServiceTest {

    private val service = EmailCleaningService()

    @Test
    fun `removes gmail quote container from html`() {
        val html = """
            <div dir="ltr">Dzień dobry, ile za ceramikę?<br></div>
            <div class="gmail_quote">W dniu pt. Studio napisał(a):<blockquote>stara treść</blockquote></div>
        """.trimIndent()

        val cleaned = service.clean(html, null)

        assertTrue(cleaned.contains("ile za ceramikę"))
        assertFalse(cleaned.contains("stara treść"))
    }

    @Test
    fun `cuts plain text at the polish quote marker and drops quoted lines`() {
        val text = """
            Dzięki za wycenę, pasuje termin.

            W dniu 12.08.2026 Jan Kowalski napisał(a):
            > poprzednia wiadomość
        """.trimIndent()

        val cleaned = service.clean(null, text)

        assertTrue(cleaned.contains("pasuje termin"))
        assertFalse(cleaned.contains("poprzednia wiadomość"))
        assertFalse(cleaned.contains("napisał"))
    }

    @Test
    fun `cuts the signature block`() {
        val cleaned = service.clean(null, "Proszę o wycenę.\n\nPozdrawiam\nJan Kowalski\ntel. 601 234 567")

        assertTrue(cleaned.contains("Proszę o wycenę"))
        assertFalse(cleaned.contains("601 234 567"))
    }

    @Test
    fun `keeps html line structure readable`() {
        val cleaned = service.clean("<p>Pierwsza linia</p><p>Druga linia</p>", null)

        assertTrue(cleaned.contains("Pierwsza linia"))
        assertTrue(cleaned.contains("Druga linia"))
        assertFalse(cleaned.contains("Pierwsza liniaDruga"))
    }

    @Test
    fun `caps the stored body length`() {
        val cleaned = service.clean(null, "x".repeat(20_000))

        assertTrue(cleaned.length <= EmailCleaningService.MAX_CLEAN_LENGTH)
    }
}

class MailAutodiscoverServiceTest {

    private val service = MailAutodiscoverService()

    @Test
    fun `routes gmail and outlook to oauth without touching the network`() {
        assertEquals(MailProviderType.GOOGLE_API, service.detect("studio@gmail.com").providerType)
        assertEquals(MailProviderType.MS_GRAPH, service.detect("studio@outlook.com").providerType)
    }

    @Test
    fun `known polish provider resolves to its imap and smtp hosts`() {
        val detection = service.detect("studio@wp.pl")

        assertEquals(MailProviderType.IMAP_SMTP, detection.providerType)
        assertEquals("imap.wp.pl", detection.imapHost)
        assertEquals(993, detection.imapPort)
        assertEquals("smtp.wp.pl", detection.smtpHost)
    }

    @Test
    fun `icloud is flagged as requiring an app password`() {
        val detection = service.detect("studio@icloud.com")

        assertTrue(detection.requiresAppPassword)
        assertNotNull(detection.guideUrl)
    }

    @Test
    fun `parses thunderbird autoconfig xml`() {
        val xml = """
            <clientConfig version="1.1"><emailProvider id="example.pl">
              <incomingServer type="imap"><hostname>imap.example.pl</hostname><port>993</port></incomingServer>
              <incomingServer type="pop3"><hostname>pop.example.pl</hostname><port>995</port></incomingServer>
              <outgoingServer type="smtp"><hostname>smtp.example.pl</hostname><port>465</port></outgoingServer>
            </emailProvider></clientConfig>
        """.trimIndent()

        val detection = service.parseAutoconfig(xml)

        assertNotNull(detection)
        assertEquals("imap.example.pl", detection!!.imapHost)
        assertEquals(993, detection.imapPort)
        assertEquals("smtp.example.pl", detection.smtpHost)
        assertEquals(465, detection.smtpPort)
    }
}

class MailboxEncryptionServiceTest {

    private val service = MailboxEncryptionService("test-secret-key-for-unit-tests")

    @Test
    fun `round trips a password`() {
        val encrypted = service.encrypt("tajne-haslo-123")

        assertTrue(encrypted.startsWith("ENC:"))
        assertFalse(encrypted.contains("tajne-haslo-123"))
        assertEquals("tajne-haslo-123", service.decrypt(encrypted))
    }

    @Test
    fun `same plaintext encrypts differently thanks to a random iv`() {
        assertFalse(service.encrypt("haslo") == service.encrypt("haslo"))
    }

    @Test
    fun `encrypt and decrypt are idempotent on already-converted values`() {
        val encrypted = service.encrypt("haslo")

        assertEquals(encrypted, service.encrypt(encrypted))
        assertEquals("jawny-tekst", service.decrypt("jawny-tekst"))
    }
}
