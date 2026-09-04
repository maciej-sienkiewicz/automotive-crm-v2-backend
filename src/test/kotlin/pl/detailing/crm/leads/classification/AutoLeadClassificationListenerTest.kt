package pl.detailing.crm.leads.classification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommInboundMessageStoredEvent
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.formmail.FormMailSourceEntity
import pl.detailing.crm.leads.formmail.FormMailSourceRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

/**
 * Kaskada odsiewu przed modelem.
 *
 * Przez ten listener przechodzi KAŻDA przychodząca wiadomość każdego studia, a tylko
 * ułamek z nich jest zapytaniem klienta. Każdy próg, który tu przecieknie, kosztuje
 * pieniądze przy każdym mailu — i to nie w testowym scenariuszu, tylko w każdej minucie
 * pracy systemu. Dlatego sprawdzamy nie „czy działa”, ale „czy NIE dochodzi do modelu”.
 */
class AutoLeadClassificationListenerTest {

    private val studioSettingsRepository = mockk<StudioSettingsRepository>()
    private val sourceRepository = mockk<FormMailSourceRepository>()
    private val messageRepository = mockk<CommMessageRepository>()
    private val threadRepository = mockk<CommThreadRepository>()
    private val leadRepository = mockk<LeadRepository>()
    private val classificationRepository = mockk<LeadMessageClassificationRepository>()
    private val processor = mockk<AutoLeadProcessor>(relaxed = true)

    private val studioId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()
    private val messageId = UUID.randomUUID()
    private val enabledAt: Instant = Instant.now().minus(30, ChronoUnit.DAYS)

    private fun listener(globallyEnabled: Boolean = true) = AutoLeadClassificationListener(
        studioSettingsRepository, sourceRepository, messageRepository, threadRepository,
        leadRepository, classificationRepository, processor, globallyEnabled
    )

    private fun event(
        sentAt: Instant = Instant.now(),
        automated: Boolean = false,
        from: String = "klient@example.com"
    ) = CommInboundMessageStoredEvent(
        studioId = studioId,
        accountId = UUID.randomUUID(),
        threadId = threadId,
        messageId = messageId,
        fromEmail = from,
        sentAt = sentAt,
        automated = automated
    )

    private fun settings(enabled: Boolean = true, stamp: Instant? = enabledAt) =
        StudioSettingsEntity(studioId = studioId).apply {
            autoLeadClassificationEnabled = enabled
            autoLeadClassificationEnabledAt = stamp
        }

    private fun thread(leadId: UUID? = null, inbound: Int = 1) = CommThreadEntity(
        id = threadId,
        studioId = studioId,
        accountId = UUID.randomUUID(),
        subjectNorm = "wycena",
        subject = "Wycena",
        participantEmail = "klient@example.com",
        participantName = "Jan Kowalski",
        lastMessageAt = Instant.now(),
        lastDirection = CommDirection.INBOUND,
        lastSnippet = "Ile kosztuje…",
        leadId = leadId,
        labelId = null
    ).also { it.inboundCount = inbound }

    private fun message() = CommMessageEntity(
        id = messageId,
        studioId = studioId,
        accountId = UUID.randomUUID(),
        threadId = threadId,
        direction = CommDirection.INBOUND,
        folderKind = CommFolderKind.INBOX,
        messageIdHdr = "msg-1",
        inReplyTo = null,
        referencesIds = null,
        fromEmail = "klient@example.com",
        fromName = "Jan Kowalski",
        toEmails = "studio@example.com",
        ccEmails = null,
        subject = "Wycena",
        sentAt = Instant.now(),
        bodyHtmlSafe = null,
        bodyText = "Ile kosztuje oklejenie BMW M3?",
        bodyTextClean = "Ile kosztuje oklejenie BMW M3?",
        imapUid = 1L,
        imapUidValidity = 1L,
        readSource = null,
        readAt = null,
        sendStatus = CommSendStatus.RECEIVED
    )

    /** Domyślnie: wszystko przepuszcza aż do procesora. */
    @BeforeEach
    fun setUp() {
        every { studioSettingsRepository.findById(studioId) } returns Optional.of(settings())
        every { sourceRepository.findByStudioIdAndSenderEmail(any(), any()) } returns null
        every { threadRepository.findById(threadId) } returns Optional.of(thread())
        every { leadRepository.findByThreadId(threadId) } returns null
        every { classificationRepository.existsByMessageId(messageId) } returns false
        every { messageRepository.findById(messageId) } returns Optional.of(message())
    }

    @Test
    fun `przepuszcza pierwsze zapytanie w nowym watku`() {
        listener().onInboundMessageStored(event())

        verify(exactly = 1) { processor.process(any(), any(), null) }
    }

    @Test
    fun `globalny wylacznik gasi funkcje bez pytania bazy`() {
        listener(globallyEnabled = false).onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
        verify(exactly = 0) { studioSettingsRepository.findById(any()) }
    }

    @Test
    fun `wylaczona flaga studia znaczy zachowanie sprzed wdrozenia`() {
        every { studioSettingsRepository.findById(studioId) } returns Optional.of(settings(enabled = false))

        listener().onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `studio bez wiersza ustawien nie klasyfikuje`() {
        every { studioSettingsRepository.findById(studioId) } returns Optional.empty()

        listener().onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `poczta starsza niz wlaczenie flagi odpada`() {
        // Doczytanie starego folderu po resecie UIDVALIDITY potrafi wsypać lata
        // korespondencji naraz. Bez tego progu zapłacilibyśmy za każdą wiadomość
        // i pogrzebali żywe leady pod setkami dawno obsłużonych.
        listener().onInboundMessageStored(event(sentAt = enabledAt.minus(1, ChronoUnit.DAYS)))

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `brak stempla wlaczenia nie blokuje poczty`() {
        // Studio, które zapaliło flagę przed migracją dokładającą kolumnę ze stemplem,
        // nie ma powodu tracić zapytań.
        every { studioSettingsRepository.findById(studioId) } returns Optional.of(settings(stamp = null))

        listener().onInboundMessageStored(event(sentAt = Instant.now().minus(400, ChronoUnit.DAYS)))

        verify(exactly = 1) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `newsletter rozpoznany po naglowkach nie dochodzi do modelu`() {
        listener().onInboundMessageStored(event(automated = true))

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `watek bedacy juz leadem odpada`() {
        every { threadRepository.findById(threadId) } returns Optional.of(thread(leadId = UUID.randomUUID()))

        listener().onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `lead wskazujacy watek odpada, nawet gdy watek o tym nie wie`() {
        // Rozjazd między leads.thread_id a comm_threads.lead_id nie może kończyć się
        // drugim leadem na tej samej rozmowie.
        every { leadRepository.findByThreadId(threadId) } returns mockk()

        listener().onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `kolejna wiadomosc w watku nie jest nowym zapytaniem`() {
        every { threadRepository.findById(threadId) } returns Optional.of(thread(inbound = 3))

        listener().onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `wiadomosc znana dziennikowi nie jest czytana ponownie`() {
        every { classificationRepository.existsByMessageId(messageId) } returns true

        listener().onInboundMessageStored(event())

        verify(exactly = 0) { processor.process(any(), any(), any()) }
        verify(exactly = 0) { messageRepository.findById(any()) }
    }

    @Test
    fun `kazdy mail robota formularza jest klasyfikowany osobno`() {
        // Powiadomienia formularza mają jednego nadawcę i jeden temat, więc IMAP skleja
        // je w JEDEN wątek — a każde z nich jest zgłoszeniem innego człowieka. Reguła
        // „tylko pierwsza w wątku” zrobiłaby z całego formularza jednego leada.
        val source = FormMailSourceEntity(studioId = studioId, senderEmail = "wordpress@studio.pl")
        every { sourceRepository.findByStudioIdAndSenderEmail(studioId, "wordpress@studio.pl") } returns source
        every { threadRepository.findById(threadId) } returns Optional.of(thread(inbound = 47))

        listener().onInboundMessageStored(event(from = "WordPress@Studio.pl"))

        verify(exactly = 1) { processor.process(any(), any(), source) }
    }

    @Test
    fun `wylaczone zrodlo formularza wraca na sciezke zwyklego nadawcy`() {
        val source = FormMailSourceEntity(studioId = studioId, senderEmail = "wordpress@studio.pl")
            .apply { active = false }
        every { sourceRepository.findByStudioIdAndSenderEmail(any(), any()) } returns source
        every { threadRepository.findById(threadId) } returns Optional.of(thread(inbound = 47))

        listener().onInboundMessageStored(event(from = "wordpress@studio.pl"))

        // Zwykła ścieżka, a tam 47. wiadomość w wątku nie jest nowym zapytaniem.
        verify(exactly = 0) { processor.process(any(), any(), any()) }
    }

    @Test
    fun `awaria procesora nie wychodzi na zewnatrz`() {
        // Listener biegnie w puli async po commicie syncu. Wyjątek stąd nie ma już czego
        // wycofać, a zalałby logi przy każdej wiadomości.
        every { processor.process(any(), any(), any()) } throws RuntimeException("baza padła")

        assertTrue(
            runCatching { listener().onInboundMessageStored(event()) }.isSuccess,
            "Automat leadów nie może wywrócić przetwarzania poczty"
        )
    }
}
