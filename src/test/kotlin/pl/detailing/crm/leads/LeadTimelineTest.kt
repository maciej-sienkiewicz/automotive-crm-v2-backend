package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.callback.LeadCallbackEntity
import pl.detailing.crm.leads.callback.LeadCallbackRepository
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryEntity
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.query.LeadQueryHandlers
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Oś czasu leada ma opowiadać PRZEBIEG SPRAWY, nie samą wędrówkę po statusach.
 *
 * Zgłoszenie z produkcji: lead po wymianie trzech maili pokazywał dwie linijki —
 * „Nowy" i „W kontakcie" — i nie dało się z niego odczytać ani o co klient pytał,
 * ani kiedy odpisaliśmy, ani co odpowiedział. Te fakty istniały, tylko w wątku
 * poczty, czyli wszędzie, byle nie tam, gdzie użytkownik ich szukał.
 */
class LeadTimelineTest {

    private val leadRepository = mockk<LeadRepository>()
    private val itemRepository = mockk<LeadServiceItemRepository>()
    private val historyRepository = mockk<LeadStatusHistoryRepository>()
    private val tagService = mockk<LeadTagService>()
    private val tagCatalog = mockk<LeadTagCatalogService>()
    private val conversationStates = mockk<LeadConversationStateService>()
    private val messageRepository = mockk<CommMessageRepository>()
    private val callbackRepository = mockk<LeadCallbackRepository>()

    private val handlers = LeadQueryHandlers(
        leadRepository, itemRepository, historyRepository, tagService, tagCatalog,
        conversationStates, messageRepository, callbackRepository
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val leadId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()
    private val start: Instant = Instant.parse("2026-09-04T13:23:23Z")

    private fun lead(thread: UUID? = threadId) = LeadEntity(
        id = leadId,
        studioId = studioId.value,
        source = LeadSource.EMAIL,
        status = LeadStatus.IN_PROGRESS,
        contactIdentifier = "sienkiewicz.maciej971030@gmail.com",
        customerName = "Maciej Sienkiewicz",
        initialMessage = "ile za oklejenie full body porsze panamera?",
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
        body: String?,
        clean: String? = body,
        fromName: String? = null
    ) = CommMessageEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        accountId = UUID.randomUUID(),
        threadId = threadId,
        direction = direction,
        folderKind = if (direction == CommDirection.INBOUND) CommFolderKind.INBOX else CommFolderKind.SENT,
        messageIdHdr = UUID.randomUUID().toString(),
        inReplyTo = null,
        referencesIds = null,
        fromEmail = if (direction == CommDirection.INBOUND) "klient@example.com" else "studio@example.com",
        fromName = fromName,
        toEmails = null,
        ccEmails = null,
        subject = "porshe panamerak",
        sentAt = sentAt,
        bodyHtmlSafe = null,
        bodyText = body,
        bodyTextClean = clean,
        imapUid = null,
        imapUidValidity = null,
        readSource = null,
        readAt = null,
        sendStatus = if (direction == CommDirection.INBOUND) CommSendStatus.RECEIVED else CommSendStatus.SENT
    )

    private fun statusChange(at: Instant, to: LeadStatus, from: LeadStatus?) = LeadStatusHistoryEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        leadId = leadId,
        fromStatus = from,
        toStatus = to,
        lostReasonCode = null,
        changedByUserId = null,
        changedByName = "Maciej Sienkiewicz",
        createdAt = at
    )

    @BeforeEach
    fun setUp() {
        every { leadRepository.findByIdAndStudioId(leadId, studioId.value) } returns lead()
        every { historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns emptyList()
        every { callbackRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns emptyList()
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns emptyList()
    }

    @Test
    fun `korespondencja i statusy skladaja sie na jedna os czasu`() {
        // Dokładnie ten wątek, na który poskarżył się użytkownik: pytanie klienta,
        // nasza wycena, jego kontroferta — a w historii widniały dwie linijki.
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns listOf(
            message(CommDirection.INBOUND, start, "ile za oklejenie full body porsze panamera?"),
            message(CommDirection.OUTBOUND, start.plusSeconds(105), "1200 dla Ciebie"),
            message(CommDirection.INBOUND, start.plusSeconds(145), "za drogo. 800 dam")
        )
        every { historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns listOf(
            statusChange(start, LeadStatus.NEW, null),
            statusChange(start.plusSeconds(105), LeadStatus.IN_PROGRESS, LeadStatus.NEW)
        )

        val timeline = handlers.timeline(studioId, leadId)

        assertEquals(
            listOf("INBOUND_MESSAGE", "STATUS", "OUTBOUND_MESSAGE", "STATUS", "INBOUND_MESSAGE"),
            timeline.map { it.kind }
        )
        assertEquals("za drogo. 800 dam", timeline.last().body)
    }

    @Test
    fun `wiadomosc stoi przed statusem, ktory sama wywolala`() {
        // Odpowiedź STEMPLUJE przejście na „W kontakcie", więc obie rzeczy mają ten sam
        // znacznik czasu. Kolejność alfabetyczna czy losowa pokazywałaby skutek przed
        // przyczyną — „W kontakcie", a pod spodem mail, który to spowodował.
        val at = start.plusSeconds(60)
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns
            listOf(message(CommDirection.OUTBOUND, at, "1200 dla Ciebie"))
        every { historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns
            listOf(statusChange(at, LeadStatus.IN_PROGRESS, LeadStatus.NEW))

        val timeline = handlers.timeline(studioId, leadId)

        assertEquals(listOf("OUTBOUND_MESSAGE", "STATUS"), timeline.map { it.kind })
    }

    @Test
    fun `telefon do klienta jest zdarzeniem osi czasu, z notatka albo bez`() {
        every { callbackRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns listOf(
            LeadCallbackEntity(
                studioId = studioId.value,
                leadId = leadId,
                note = "prosił o kontakt po 15",
                calledByName = "Maciej Sienkiewicz",
                createdAt = start
            ),
            LeadCallbackEntity(
                studioId = studioId.value,
                leadId = leadId,
                note = null,
                calledByName = "Maciej Sienkiewicz",
                createdAt = start.plusSeconds(3600)
            )
        )

        val timeline = handlers.timeline(studioId, leadId)

        assertEquals(listOf("CALLBACK", "CALLBACK"), timeline.map { it.kind })
        assertEquals("prosił o kontakt po 15", timeline[0].note)
        assertNull(timeline[1].note, "Sam fakt telefonu bywa całą informacją")
        assertEquals("Maciej Sienkiewicz", timeline[0].actorName)
    }

    @Test
    fun `podglad pokazuje tresc bez zacytowanej historii watku`() {
        // Odpowiedź klienta to zwykle jedno zdanie i kopia całej rozmowy pod spodem.
        // Pokazanie tej kopii zamieniłoby oś czasu w kolejny przebieg tego samego wątku.
        val quoted = "za drogo. 800 dam\n\npt., 4 wrz 2026 o 15:25 napisał(a):\n> 1200 dla Ciebie"
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns
            listOf(message(CommDirection.INBOUND, start, quoted, clean = "za drogo. 800 dam"))

        assertEquals("za drogo. 800 dam", handlers.timeline(studioId, leadId).single().body)
    }

    @Test
    fun `bez wersji oczyszczonej zostaje surowa tresc`() {
        // Lepszy nieociosany oryginał niż zdarzenie, z którego nic nie wynika.
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns
            listOf(message(CommDirection.INBOUND, start, "surowa treść", clean = "   "))

        assertEquals("surowa treść", handlers.timeline(studioId, leadId).single().body)
    }

    @Test
    fun `klient bez nazwy w naglowku jest podpisany tak, jak zna go CRM`() {
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns
            listOf(message(CommDirection.INBOUND, start, "pytanie", fromName = null))

        assertEquals("Maciej Sienkiewicz", handlers.timeline(studioId, leadId).single().actorName)
    }

    @Test
    fun `lead bez watku nie pyta o wiadomosci`() {
        // Lead z telefonu albo z formularza nie ma korespondencji — i nie ma powodu,
        // żeby jej szukać.
        every { leadRepository.findByIdAndStudioId(leadId, studioId.value) } returns lead(thread = null)
        every { historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns
            listOf(statusChange(start, LeadStatus.NEW, null))

        val timeline = handlers.timeline(studioId, leadId)

        assertEquals(listOf("STATUS"), timeline.map { it.kind })
        assertTrue(timeline.single().body == null)
    }
}
