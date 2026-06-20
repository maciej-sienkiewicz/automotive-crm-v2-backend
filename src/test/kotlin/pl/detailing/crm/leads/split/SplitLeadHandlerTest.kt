package pl.detailing.crm.leads.split

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.comments.LeadCommentEntity
import pl.detailing.crm.leads.comments.LeadCommentRepository
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.create.CreateLeadResult
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.Optional
import java.util.UUID

class SplitLeadHandlerTest {

    private val leadRepository = mockk<LeadRepository>()
    private val commentRepository = mockk<LeadCommentRepository>()
    private val createLeadHandler = mockk<CreateLeadHandler>()
    private val auditService = mockk<AuditService>(relaxed = true)

    private val handler = SplitLeadHandler(
        leadRepository,
        commentRepository,
        createLeadHandler,
        auditService
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val sourceLeadId = LeadId.random()
    private val commentId = UUID.randomUUID()
    private val newLeadId = LeadId.random()

    @BeforeEach
    fun setUp() {
        every { leadRepository.findById(sourceLeadId.value) } returns Optional.of(sourceLead())
        coEvery { createLeadHandler.handle(any()) } returns createLeadResult()
        every { commentRepository.reassignComment(any(), any()) } returns 1
    }

    @Test
    fun `creates a new lead from the comment content and moves the comment to it`() = runBlocking {
        every { commentRepository.findActiveByIdAndStudioId(commentId, studioId.value) } returns
            comment(leadId = sourceLeadId.value, content = "Interesuje mnie powłoka ceramiczna")

        val slot = slot<CreateLeadCommand>()
        coEvery { createLeadHandler.handle(capture(slot)) } returns createLeadResult()

        val result = handler.handle(baseCommand())

        assertEquals(newLeadId, result.newLeadId)
        assertEquals("Interesuje mnie powłoka ceramiczna", slot.captured.initialMessage)
        assertEquals("klient@example.com", slot.captured.contactIdentifier)
        assertEquals(LeadSource.EMAIL, slot.captured.source)
        verify(exactly = 1) { commentRepository.reassignComment(commentId, newLeadId.value) }
        verify { auditService.log(match<LogAuditCommand> { it.action == AuditAction.LEAD_SPLIT }) }
    }

    @Test
    fun `throws when comment belongs to a different lead`() = runBlocking {
        every { commentRepository.findActiveByIdAndStudioId(commentId, studioId.value) } returns
            comment(leadId = UUID.randomUUID(), content = "obcy komentarz")

        assertThrows<ForbiddenException> { runBlocking { handler.handle(baseCommand()) } }
        coVerify(exactly = 0) { createLeadHandler.handle(any()) }
    }

    @Test
    fun `throws when lead belongs to a different studio`() = runBlocking {
        every { leadRepository.findById(sourceLeadId.value) } returns
            Optional.of(sourceLead(studio = UUID.randomUUID()))

        assertThrows<ForbiddenException> { runBlocking { handler.handle(baseCommand()) } }
    }

    private fun baseCommand() = SplitLeadCommand(
        sourceLeadId = sourceLeadId,
        commentId = commentId,
        studioId = studioId,
        userId = userId,
        userName = "Pracownik"
    )

    private fun sourceLead(studio: UUID = studioId.value) = mockk<LeadEntity>(relaxed = true) {
        every { this@mockk.studioId } returns studio
        every { source } returns LeadSource.EMAIL
        every { contactIdentifier } returns "klient@example.com"
        every { customerName } returns "Jan Kowalski"
        every { customerId } returns null
    }

    private fun comment(leadId: UUID, content: String) = mockk<LeadCommentEntity>(relaxed = true) {
        every { this@mockk.leadId } returns leadId
        every { this@mockk.content } returns content
    }

    private fun createLeadResult() = CreateLeadResult(
        leadId = newLeadId,
        source = LeadSource.EMAIL,
        status = LeadStatus.NEW,
        contactIdentifier = "klient@example.com",
        customerName = "Jan Kowalski",
        initialMessage = "Interesuje mnie powłoka ceramiczna",
        estimatedValue = 0L,
        requiresVerification = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
