package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.convert.MarkThreadAsLeadCommand
import pl.detailing.crm.leads.convert.MarkThreadAsLeadHandler
import pl.detailing.crm.leads.domain.LeadTag
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadServiceItemsService
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Status leada ma opisywać stan rozmowy, a nie moment kliknięcia.
 *
 * Kolejność bywa dowolna: czasem oznaczamy leada z nietkniętego zapytania, a czasem
 * najpierw odpisujemy klientowi i dopiero potem orientujemy się, że to lead. Gdyby
 * w tym drugim przypadku lead trafiał do tabeli jako „Nowy", użytkownik przestałby
 * ufać statusom i zaczął poprawiać je ręcznie.
 */
class MarkThreadAsLeadStatusTest {

    private val threadRepository = mockk<CommThreadRepository>(relaxed = true)
    private val leadRepository = mockk<LeadRepository>(relaxed = true)
    private val customerRepository = mockk<CustomerRepository>(relaxed = true)
    private val messageRepository = mockk<CommMessageRepository>(relaxed = true)
    private val serviceItems = mockk<LeadServiceItemsService>(relaxed = true)
    private val tagService = mockk<LeadTagService>(relaxed = true)
    private val statusService = mockk<LeadStatusService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val handler = MarkThreadAsLeadHandler(
        threadRepository, leadRepository, customerRepository, messageRepository,
        serviceItems, tagService, statusService, eventPublisher
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val threadId = UUID.randomUUID()

    private fun givenThread() {
        val thread = mockk<CommThreadEntity>(relaxed = true)
        every { thread.id } returns threadId
        every { thread.participantEmail } returns "klient@example.com"
        every { thread.participantName } returns "Jan Kowalski"
        every { thread.lastSnippet } returns "Poprosze o wycene"
        every { threadRepository.findByIdAndStudioId(threadId, studioId.value) } returns thread
        every { leadRepository.findByThreadId(threadId) } returns null
        every { customerRepository.findActiveByStudioIdAndEmail(any(), any()) } returns null
    }

    private fun message(direction: CommDirection, sentAt: Instant): CommMessageEntity =
        mockk<CommMessageEntity>(relaxed = true).also {
            every { it.direction } returns direction
            every { it.sentAt } returns sentAt
        }

    private fun command() = MarkThreadAsLeadCommand(
        studioId = studioId,
        threadId = threadId,
        userId = UUID.randomUUID(),
        userName = "Jakub",
        tags = listOf(LeadTag.PPF_WRAP),
        services = emptyList()
    )

    private fun savedLead(): LeadEntity {
        val saved = slot<LeadEntity>()
        every { leadRepository.save(capture(saved)) } answers { saved.captured }
        handler.handle(command())
        return saved.captured
    }

    @Test
    fun `zapytanie bez naszej odpowiedzi zostaje nowym leadem`() {
        givenThread()
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns
            listOf(message(CommDirection.INBOUND, Instant.now()))

        val lead = savedLead()

        assertEquals(LeadStatus.NEW, lead.status)
        assertNull(lead.firstResponseAt, "Nie odpisalismy, wiec nie ma czasu pierwszej reakcji")
    }

    @Test
    fun `gdy odpisalismy przed oznaczeniem, lead od razu jest w kontakcie`() {
        givenThread()
        val replyAt = Instant.parse("2026-08-17T14:36:51Z")
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns listOf(
            message(CommDirection.INBOUND, Instant.parse("2026-08-15T18:57:33Z")),
            message(CommDirection.OUTBOUND, replyAt),
            message(CommDirection.INBOUND, Instant.parse("2026-08-18T01:38:41Z"))
        )

        val lead = savedLead()

        assertEquals(LeadStatus.IN_PROGRESS, lead.status)
        // Czas pierwszej reakcji bierzemy z faktycznej odpowiedzi, nie z chwili klikniecia --
        // inaczej raport "jak szybko odpowiadamy" mierzylby refleks operatora CRM.
        assertEquals(replyAt, lead.firstResponseAt)
    }

    @Test
    fun `tagi zapisuja sie razem z leadem`() {
        givenThread()
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns emptyList()

        val lead = savedLead()

        verify { tagService.replaceTags(lead.id, listOf(LeadTag.PPF_WRAP)) }
    }
}
