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
import pl.detailing.crm.mailbox.infrastructure.MailProviderDetection
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

/**
 * The bug this guards against: a custom domain hosted at a Polish provider fell straight
 * through to the blind imap.{domain} guess, because the cascade never looked at the MX
 * record. Thunderbird resolves such domains via MX → ISPDB(provider base domain).
 */
private class FakeAutodiscover(
    private val ispdb: Map<String, MailProviderDetection> = emptyMap(),
    private val dns: Map<Pair<String, String>, List<String>> = emptyMap(),
    private val reachableHosts: Set<String> = emptySet()
) : MailAutodiscoverService() {
    override fun fetchFromIspdb(domain: String): MailProviderDetection? = ispdb[domain]
    override fun dnsRecords(name: String, type: String): List<String> = dns[name to type] ?: emptyList()
    override fun tcpReachable(host: String, port: Int): Boolean = host in reachableHosts
}

class MailAutodiscoverCascadeTest {

    @Test
    fun `custom domain hosted at home_pl resolves through its mx record`() {
        val service = FakeAutodiscover(
            dns = mapOf(("sienkiewicz-maciej.pl" to "MX") to listOf("10 mx.home.pl."))
        )

        val detection = service.detect("kontakt@sienkiewicz-maciej.pl")

        assertEquals("imap.home.pl", detection.imapHost)
        assertEquals(993, detection.imapPort)
        assertEquals("smtp.home.pl", detection.smtpHost)
        assertEquals(465, detection.smtpPort)
    }

    @Test
    fun `mx base domain is looked up in the ispdb for providers outside the static map`() {
        val service = FakeAutodiscover(
            ispdb = mapOf(
                "megiteam.pl" to MailAutodiscoverService.imapDetection(
                    "imap.megiteam.pl", 993, "smtp.megiteam.pl", 465
                )
            ),
            dns = mapOf(("firma.pl" to "MX") to listOf("5 serwer21.megiteam.pl."))
        )

        val detection = service.detect("biuro@firma.pl")

        assertEquals("imap.megiteam.pl", detection.imapHost)
    }

    @Test
    fun `srv records win over mx heuristics when their target answers`() {
        val service = FakeAutodiscover(
            dns = mapOf(
                ("_imaps._tcp.firma.pl" to "SRV") to listOf("0 1 993 imap.hosting.eu."),
                ("firma.pl" to "MX") to listOf("10 mx.home.pl.")
            ),
            reachableHosts = setOf("imap.hosting.eu")
        )

        val detection = service.detect("biuro@firma.pl")

        assertEquals("imap.hosting.eu", detection.imapHost)
        assertEquals(993, detection.imapPort)
    }

    @Test
    fun `template srv record pointing at the www server is ignored and mx wins`() {
        val service = FakeAutodiscover(
            dns = mapOf(
                ("_imaps._tcp.sienkiewicz-maciej.pl" to "SRV") to listOf("0 1 993 sienkiewicz-maciej.pl."),
                ("sienkiewicz-maciej.pl" to "MX") to listOf("10 mx.home.pl.")
            )
            // reachableHosts puste: cel SRV nie odpowiada na 993, jak w zgłoszeniu
        )

        val detection = service.detect("kontakt@sienkiewicz-maciej.pl")

        assertEquals("imap.home.pl", detection.imapHost)
        assertEquals("smtp.home.pl", detection.smtpHost)
    }

    @Test
    fun `convention guess is returned only when the host actually answers`() {
        val service = FakeAutodiscover(reachableHosts = setOf("mail.firma.pl"))

        val detection = service.detect("biuro@firma.pl")

        assertEquals("mail.firma.pl", detection.imapHost)
    }

    @Test
    fun `with nothing to go on the blind guess remains as a manual-form seed`() {
        val detection = FakeAutodiscover().detect("biuro@firma.pl")

        assertEquals("imap.firma.pl", detection.imapHost)
    }

    @Test
    fun `ispdb placeholders are substituted with the client domain`() {
        val service = FakeAutodiscover(
            ispdb = mapOf(
                "firma.pl" to MailAutodiscoverService.imapDetection(
                    "mail.%EMAILDOMAIN%", 993, "mail.%EMAILDOMAIN%", 465
                )
            )
        )

        val detection = service.detect("biuro@firma.pl")

        assertEquals("mail.firma.pl", detection.imapHost)
        assertEquals("mail.firma.pl", detection.smtpHost)
    }
}

class MxSrvParsingTest {

    @Test
    fun `mx records are sorted by priority and stripped of trailing dots`() {
        val hosts = MailAutodiscoverService.parseMxRecords(
            listOf("20 backup.home.pl.", "10 MX.Home.PL.", "zepsuty rekord bez priorytetu x")
        )
        assertEquals(listOf("mx.home.pl", "backup.home.pl"), hosts)
    }

    @Test
    fun `srv null target means service explicitly absent`() {
        assertTrue(MailAutodiscoverService.parseSrvRecords(listOf("0 1 993 .")).isEmpty())
        assertEquals(
            listOf("imap.hosting.eu" to 993),
            MailAutodiscoverService.parseSrvRecords(listOf("0 1 993 imap.hosting.eu."))
        )
    }

    @Test
    fun `base domain candidates prefer the two-label suffix and skip the own domain`() {
        val candidates = MailAutodiscoverService.mxBaseDomainCandidates(
            listOf("mx.home.pl"), ownDomain = "sienkiewicz-maciej.pl"
        )
        assertEquals(listOf("home.pl", "mx.home.pl"), candidates)

        val selfHosted = MailAutodiscoverService.mxBaseDomainCandidates(
            listOf("mail.firma.pl"), ownDomain = "firma.pl"
        )
        assertEquals(listOf("mail.firma.pl"), selfHosted)
    }
}
