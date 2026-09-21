package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.conversation.FormLeadConversation
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.formmail.FormMailExtractionEntity
import pl.detailing.crm.leads.formmail.FormMailExtractionRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.UUID

/**
 * Rozmowa leada z formularza WWW.
 *
 * Zgłoszenie z produkcji: klient napisał przez formularz, CRM założył leada - i lead
 * był PUSTY. Ani jednej wiadomości na osi czasu, a po wysłaniu odpowiedzi sprawa nadal
 * wisiała jako nieodpisana. Przyczyna: robot formularza wysyła wszystkie zgłoszenia
 * z jednego adresu, serwer skleja je w jeden wątek (249 wiadomości od kilkudziesięciu
 * osób), więc lead celowo nie ma `threadId` - a całe CRM-owe pojęcie rozmowy szło
 * właśnie przez wątek.
 */
class FormLeadConversationTest {

    private val extractionRepository = mockk<FormMailExtractionRepository>()
    private val messageRepository = mockk<CommMessageRepository>(relaxed = true)
    private val conversation = FormLeadConversation(extractionRepository, messageRepository)

    private val studioId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()
    private val start: Instant = Instant.parse("2026-09-21T10:05:28Z")

    private val zbyszko = "biniollo.rosso@gmail.com"

    private fun lead(contact: String = zbyszko, thread: UUID? = null) = LeadEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        source = LeadSource.FORM,
        status = LeadStatus.NEW,
        contactIdentifier = contact,
        customerName = "Zbyszko",
        initialMessage = "Potrzebuję przygotować auto do sprzedaży",
        estimatedValue = 0,
        requiresVerification = false,
        vehicleBrand = null,
        vehicleModel = null,
        vehicleDetectionStatus = LeadVehicleDetectionStatus.DONE,
        customerId = null,
        appointmentId = null,
        visitId = null,
        assignedUserId = null,
        assignedUserName = null,
        lostReason = null,
        stagnantAlertSentAt = null,
        threadId = thread,
        category = null,
        firstResponseAt = null
    )

    private fun message(
        direction: CommDirection,
        sentAt: Instant,
        from: String,
        to: String?
    ) = CommMessageEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        accountId = UUID.randomUUID(),
        threadId = threadId,
        direction = direction,
        folderKind = if (direction == CommDirection.INBOUND) CommFolderKind.INBOX else CommFolderKind.SENT,
        messageIdHdr = UUID.randomUUID().toString(),
        inReplyTo = null,
        referencesIds = null,
        fromEmail = from,
        fromName = if (direction == CommDirection.INBOUND) "Carslab" else "Biuro CarsLab",
        toEmails = to,
        ccEmails = null,
        subject = "Formularz kontaktowy - carslab - kontakt",
        sentAt = sentAt,
        bodyHtmlSafe = null,
        bodyText = "treść",
        bodyTextClean = "treść",
        imapUid = null,
        imapUidValidity = null,
        readSource = null,
        readAt = null,
        sendStatus = if (direction == CommDirection.INBOUND) CommSendStatus.RECEIVED else CommSendStatus.SENT
    )

    /** Zgłoszenie robota: przychodzi z adresu studia na adres studia, nie od klienta. */
    private fun formSubmission(at: Instant = start) =
        message(CommDirection.INBOUND, at, from = "biuro@carslab.pl", to = "biuro@carslab.pl")

    private fun linkOrigin(leadId: UUID, origin: CommMessageEntity) {
        every { extractionRepository.findByLeadIdIn(any()) } returns listOf(
            FormMailExtractionEntity(
                studioId = studioId,
                sourceId = UUID.randomUUID(),
                messageId = origin.id,
                status = "CREATED",
                leadId = leadId
            )
        )
        every { messageRepository.findAllById(any<Iterable<UUID>>()) } returns listOf(origin)
    }

    @Test
    fun `zgloszenie z formularza trafia na osi czasu leada`() {
        // Sedno zgłoszenia: lead był pusty, choć wiadomość istniała w skrzynce.
        val lead = lead()
        val origin = formSubmission()
        linkOrigin(lead.id, origin)
        every { messageRepository.findCounterpartyMessages(any(), any(), any(), any()) } returns emptyList()

        val messages = conversation.messagesOf(lead)

        assertEquals(listOf(origin.id), messages.map { it.id })
    }

    @Test
    fun `odpowiedz studia do tego klienta dolacza do rozmowy`() {
        val lead = lead()
        val origin = formSubmission()
        val reply = message(CommDirection.OUTBOUND, start.plusSeconds(93), from = "biuro@carslab.pl", to = zbyszko)
        linkOrigin(lead.id, origin)
        every { messageRepository.findCounterpartyMessages(studioId, threadId, zbyszko, origin.sentAt) } returns
            listOf(reply)

        val messages = conversation.messagesOf(lead)

        assertEquals(listOf(origin.id, reply.id), messages.map { it.id })
    }

    @Test
    fun `po odpowiedzi sprawa przestaje byc nieodpisana`() {
        /*
         * To jest druga połowa zgłoszenia: odpowiedź do Zbyszka poszła o 10:17,
         * a lead dalej miał `lastOutboundAt` puste i wisiał w „Czeka na Ciebie".
         */
        val lead = lead()
        val origin = formSubmission()
        linkOrigin(lead.id, origin)
        every { messageRepository.findCounterpartyRows(any(), any()) } returns listOf(
            arrayOf(threadId, CommDirection.OUTBOUND, "biuro@carslab.pl", zbyszko, start.plusSeconds(93))
        )

        val state = conversation.statesOf(studioId, listOf(lead))[lead.id]!!

        assertEquals(start.plusSeconds(93), state.lastOutboundAt)
        assertEquals(start, state.lastInboundAt)
    }

    @Test
    fun `korespondencja innych klientow z tego samego watku nie wchodzi`() {
        /*
         * Wątek robota zbiera zgłoszenia wszystkich - u tego studia 249 wiadomości od
         * kilkudziesięciu osób. Wciągnięcie ich pokazałoby Zbyszkowi cudzą korespondencję
         * i byłoby gorsze niż pusta oś czasu.
         */
        val lead = lead()
        val origin = formSubmission()
        linkOrigin(lead.id, origin)
        every { messageRepository.findCounterpartyRows(any(), any()) } returns listOf(
            arrayOf(threadId, CommDirection.OUTBOUND, "biuro@carslab.pl", "g.majdan@gmail.com", start.plusSeconds(60)),
            arrayOf(threadId, CommDirection.INBOUND, "g.majdan@gmail.com", "biuro@carslab.pl", start.plusSeconds(90))
        )

        val state = conversation.statesOf(studioId, listOf(lead))[lead.id]!!

        assertEquals(null, state.lastOutboundAt, "wciągnięto odpowiedź do innego klienta")
        assertEquals(start, state.lastInboundAt)
    }

    @Test
    fun `korespondencja sprzed zgloszenia nalezy do starszej sprawy`() {
        // Ten sam klient pisał w czerwcu i wrócił we wrześniu: to dwie sprawy,
        // a starsza korespondencja należy do starszego leada.
        val lead = lead()
        val origin = formSubmission()
        linkOrigin(lead.id, origin)
        every { messageRepository.findCounterpartyRows(any(), any()) } returns listOf(
            arrayOf(threadId, CommDirection.OUTBOUND, "biuro@carslab.pl", zbyszko, start.minusSeconds(3600))
        )

        val state = conversation.statesOf(studioId, listOf(lead))[lead.id]!!

        assertEquals(null, state.lastOutboundAt)
    }

    @Test
    fun `zgloszenie z samym telefonem zostaje przy swojej wiadomosci`() {
        // Bez adresu nie ma czego dopasować w wątku. Dopasowywanie po numerze
        // z treści maila byłoby zgadywaniem, więc go nie ma.
        val lead = lead(contact = "798892677")
        val origin = formSubmission()
        linkOrigin(lead.id, origin)

        val messages = conversation.messagesOf(lead)

        assertEquals(listOf(origin.id), messages.map { it.id })
    }

    @Test
    fun `lead z wlasnym watkiem nie przechodzi ta sciezka`() {
        // Zwykła korespondencja ma swój wątek i liczy się jak dotąd.
        val messages = conversation.messagesOf(lead(thread = UUID.randomUUID()))

        assertTrue(messages.isEmpty())
    }
}
