package pl.detailing.crm.leads.comments

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.Optional
import java.util.UUID

class LeadCommentHandlerTest {

    private val leadRepository = mockk<LeadRepository>()
    private val commentRepository = mockk<LeadCommentRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)

    private val handler = LeadCommentHandler(leadRepository, commentRepository, auditService)

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val leadId = LeadId.random()
    private val commentId = UUID.randomUUID()

    @Test
    fun `addComment logs LEAD_COMMENT_ADDED to history`() = runBlocking {
        every { leadRepository.findById(leadId.value) } returns Optional.of(lead())
        every { commentRepository.save(any()) } answers { firstArg() }

        handler.addComment(
            AddLeadCommentCommand(
                leadId = leadId,
                studioId = studioId,
                userId = userId,
                userName = "Pracownik",
                content = "Klient zainteresowany powłoką"
            )
        )

        coVerify(exactly = 1) {
            auditService.log(match<LogAuditCommand> { it.action == AuditAction.LEAD_COMMENT_ADDED })
        }
    }

    @Test
    fun `deleteComment logs LEAD_COMMENT_DELETED to history`() = runBlocking {
        val comment = mockk<LeadCommentEntity>(relaxed = true) {
            every { this@mockk.leadId } returns leadId.value
        }
        every { commentRepository.findActiveByIdAndStudioId(commentId, studioId.value) } returns comment
        every { commentRepository.save(any()) } answers { firstArg() }

        handler.deleteComment(
            DeleteLeadCommentCommand(
                commentId = commentId,
                leadId = leadId,
                studioId = studioId,
                userId = userId,
                userName = "Pracownik"
            )
        )

        coVerify(exactly = 1) {
            auditService.log(match<LogAuditCommand> { it.action == AuditAction.LEAD_COMMENT_DELETED })
        }
    }

    private fun lead() = mockk<LeadEntity>(relaxed = true) {
        every { this@mockk.studioId } returns studioId.value
    }
}
