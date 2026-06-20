package pl.detailing.crm.leads.merge

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.comments.LeadCommentHandler
import pl.detailing.crm.leads.comments.LeadCommentRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.lostreason.UpdateLostReasonCommand
import pl.detailing.crm.leads.lostreason.UpdateLostReasonHandler
import pl.detailing.crm.leads.update.UpdateLeadCommand
import pl.detailing.crm.leads.update.UpdateLeadHandler
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class MergeLeadsHandlerTest {

    private val leadRepository = mockk<LeadRepository>()
    private val commentRepository = mockk<LeadCommentRepository>()
    private val leadCommentHandler = mockk<LeadCommentHandler>(relaxed = true)
    private val updateLeadHandler = mockk<UpdateLeadHandler>(relaxed = true)
    private val updateLostReasonHandler = mockk<UpdateLostReasonHandler>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)

    private val handler = MergeLeadsHandler(
        leadRepository,
        commentRepository,
        leadCommentHandler,
        updateLeadHandler,
        updateLostReasonHandler,
        auditService
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val sourceLeadId = LeadId.random()
    private val targetLeadId = LeadId.random()

    @BeforeEach
    fun setUp() {
        every { leadRepository.findById(sourceLeadId.value) } returns
            Optional.of(lead(sourceLeadId.value, contact = "klient@example.com"))
        every { leadRepository.findById(targetLeadId.value) } returns
            Optional.of(lead(targetLeadId.value, contact = "klient@example.com"))
        every { leadRepository.save(any()) } returns mockk(relaxed = true)
        every { commentRepository.reassignComments(any(), any()) } returns 3
    }

    @Test
    fun `moves comments to target, closes source as LOST and audits the merge`() = runBlocking {
        val statusSlot = slot<UpdateLeadCommand>()
        val reasonSlot = slot<UpdateLostReasonCommand>()
        coEvery { updateLeadHandler.handle(capture(statusSlot)) } returns mockk(relaxed = true)
        coEvery { updateLostReasonHandler.handle(capture(reasonSlot)) } returns Unit

        val result = handler.handle(baseCommand())

        assertEquals(targetLeadId, result.targetLeadId)
        verify(exactly = 1) { commentRepository.reassignComments(sourceLeadId.value, targetLeadId.value) }
        coVerify(exactly = 1) { leadCommentHandler.addComment(any()) }
        assertEquals(LeadStatus.LOST, statusSlot.captured.status)
        assertEquals(sourceLeadId, statusSlot.captured.leadId)
        assertTrue(reasonSlot.captured.lostReason!!.contains(targetLeadId.value.toString()))
        verify { auditService.log(match<LogAuditCommand> { it.action == AuditAction.LEAD_MERGED }) }
    }

    @Test
    fun `throws when merging a lead into itself`() {
        assertThrows<ValidationException> {
            runBlocking { handler.handle(baseCommand().copy(targetLeadId = sourceLeadId)) }
        }
    }

    @Test
    fun `throws when leads belong to different clients`() {
        every { leadRepository.findById(targetLeadId.value) } returns
            Optional.of(lead(targetLeadId.value, contact = "inny@example.com"))

        assertThrows<ValidationException> { runBlocking { handler.handle(baseCommand()) } }
        verify(exactly = 0) { commentRepository.reassignComments(any(), any()) }
    }

    private fun baseCommand() = MergeLeadsCommand(
        sourceLeadId = sourceLeadId,
        targetLeadId = targetLeadId,
        studioId = studioId,
        userId = userId,
        userName = "Pracownik"
    )

    private fun lead(leadId: UUID, contact: String) = mockk<LeadEntity>(relaxed = true) {
        every { id } returns leadId
        every { this@mockk.studioId } returns studioId.value
        every { contactIdentifier } returns contact
        every { customerName } returns "Jan Kowalski"
        every { initialMessage } returns "Pierwotne zapytanie"
        every { createdAt } returns Instant.now()
    }
}
