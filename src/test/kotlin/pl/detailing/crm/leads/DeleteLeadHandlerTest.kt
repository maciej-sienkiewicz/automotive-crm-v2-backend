package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.delete.DeleteLeadHandler
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.infrastructure.LeadTagRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Usunięcie leada nie ma prawa zabrać ze sobą korespondencji ani zostawić wiszącego
 * powiązania — a lead, który jest już w kalendarzu, nie ma prawa dać się usunąć.
 */
class DeleteLeadHandlerTest {

    private val leadRepository = mockk<LeadRepository>(relaxed = true)
    private val itemRepository = mockk<LeadServiceItemRepository>(relaxed = true)
    private val tagRepository = mockk<LeadTagRepository>(relaxed = true)
    private val historyRepository = mockk<LeadStatusHistoryRepository>(relaxed = true)
    private val threadRepository = mockk<CommThreadRepository>(relaxed = true)

    private val handler = DeleteLeadHandler(
        leadRepository, itemRepository, tagRepository, historyRepository, threadRepository,
        appointmentRepository = mockk(relaxed = true),
        auditService = mockk(relaxed = true)
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val leadId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    private fun delete() = handler.handle(studioId, leadId, userId, "Jan Właściciel")

    private fun givenLead(appointmentId: UUID? = null, visitId: UUID? = null): LeadEntity {
        val lead = mockk<LeadEntity>(relaxed = true)
        every { lead.id } returns leadId
        every { lead.threadId } returns threadId
        every { lead.appointmentId } returns appointmentId
        every { lead.visitId } returns visitId
        every { leadRepository.findByIdAndStudioId(leadId, studioId.value) } returns lead
        return lead
    }

    @Test
    fun `watek traci powiazanie, ale zostaje w skrzynce`() {
        val lead = givenLead()
        val thread = mockk<CommThreadEntity>(relaxed = true)
        every { threadRepository.findByIdAndStudioId(threadId, studioId.value) } returns thread
        every { threadRepository.save(any()) } answers { firstArg() }

        delete()

        verify { thread.leadId = null }
        verify { threadRepository.save(thread) }
        verify(exactly = 0) { threadRepository.delete(any()) }
        verify { leadRepository.delete(lead) }
    }

    @Test
    fun `tagi i pozycje znikaja razem z leadem`() {
        givenLead()
        every { threadRepository.findByIdAndStudioId(threadId, studioId.value) } returns null

        delete()

        verify { tagRepository.deleteByLeadId(leadId) }
        verify { itemRepository.deleteByLeadId(leadId) }
    }

    @Test
    fun `lead z rezerwacja usuwa sie, a rezerwacja domyslnie zostaje w kalendarzu`() {
        // Powiązanie jest jednokierunkowe (lead → rezerwacja); usunięcie samego leada
        // zostawia termin jako samodzielną rezerwację.
        val lead = givenLead(appointmentId = UUID.randomUUID())
        every { threadRepository.findByIdAndStudioId(threadId, studioId.value) } returns null

        delete()

        verify { leadRepository.delete(lead) }
    }

    @Test
    fun `lead z wizyta nie daje sie usunac`() {
        givenLead(visitId = UUID.randomUUID())

        assertThrows(ConflictException::class.java) { delete() }
        verify(exactly = 0) { leadRepository.delete(any()) }
    }

    @Test
    fun `lead z innego studia nie istnieje`() {
        every { leadRepository.findByIdAndStudioId(leadId, studioId.value) } returns null

        assertThrows(NotFoundException::class.java) { delete() }
    }
}
