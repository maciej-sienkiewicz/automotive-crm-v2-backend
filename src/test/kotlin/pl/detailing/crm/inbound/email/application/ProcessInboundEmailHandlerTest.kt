package pl.detailing.crm.inbound.email.application

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.inbound.email.domain.EmailClassificationResult
import pl.detailing.crm.inbound.email.domain.EmailLeadClassifier
import pl.detailing.crm.leads.comments.LeadCommentHandler
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.create.CreateLeadResult
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadHandler
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.util.UUID

class ProcessInboundEmailHandlerTest {

    private val studioRepository = mockk<StudioRepository>()
    private val emailLeadClassifier = mockk<EmailLeadClassifier>()
    private val createLeadHandler = mockk<CreateLeadHandler>()
    private val analyzeLeadHandler = mockk<AnalyzeLeadHandler>(relaxed = true)
    private val leadRepository = mockk<LeadRepository>(relaxed = true)
    private val leadCommentHandler = mockk<LeadCommentHandler>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)

    private val handler = ProcessInboundEmailHandler(
        studioRepository,
        emailLeadClassifier,
        createLeadHandler,
        analyzeLeadHandler,
        leadRepository,
        leadCommentHandler,
        eventPublisher,
        auditService
    )

    private val studioId = UUID.randomUUID()

    private val command = ProcessInboundEmailCommand(
        alias = "info@studio.pl",
        from = "klient@example.com",
        subject = "Zapytanie",
        body = "Dzień dobry, interesuje mnie powłoka ceramiczna."
    )

    @BeforeEach
    fun setUp() {
        every { studioRepository.findByEmailAlias(command.alias) } returns
            mockk<StudioEntity> { every { id } returns studioId }
        coEvery { emailLeadClassifier.classify(any(), any(), any()) } returns leadDetected()
        coEvery { createLeadHandler.handle(any()) } returns createLeadResult()
    }

    @Test
    fun `email reply to an OPEN lead is appended as a comment, not a new lead`() = runBlocking {
        val openLead = mockk<LeadEntity>(relaxed = true) { every { id } returns UUID.randomUUID() }
        every {
            leadRepository.findLatestOpenByContactIdentifier(any(), any(), any(), any())
        } returns openLead

        val result = handler.handle(command)

        assertTrue(result is ProcessInboundEmailResult.ReplyAppended)
        coVerify(exactly = 1) { leadCommentHandler.addComment(any()) }
        coVerify(exactly = 0) { createLeadHandler.handle(any()) }
    }

    @Test
    fun `email with no open lead creates a new lead`() = runBlocking {
        every {
            leadRepository.findLatestOpenByContactIdentifier(any(), any(), any(), any())
        } returns null

        val result = handler.handle(command)

        assertTrue(result is ProcessInboundEmailResult.LeadCreated)
        coVerify(exactly = 1) { createLeadHandler.handle(any()) }
        coVerify(exactly = 0) { leadCommentHandler.addComment(any()) }
    }

    @Test
    fun `dedup query is restricted to open statuses (NEW, IN_PROGRESS, CONFIRMED)`() = runBlocking {
        every {
            leadRepository.findLatestOpenByContactIdentifier(any(), any(), any(), any())
        } returns null

        handler.handle(command)

        verify(exactly = 1) {
            leadRepository.findLatestOpenByContactIdentifier(
                studioId,
                command.from,
                listOf(
                    LeadStatus.NEW.name,
                    LeadStatus.IN_PROGRESS.name,
                    LeadStatus.CONFIRMED.name
                ),
                any()
            )
        }
    }

    private fun leadDetected() = EmailClassificationResult.LeadDetected(
        extractedName = "Jan Kowalski",
        summary = "Powłoka ceramiczna",
        vehicleMake = null,
        vehicleModel = null,
        vehicleYear = null,
        requestedServices = emptyList()
    )

    private fun createLeadResult() = CreateLeadResult(
        leadId = LeadId.random(),
        source = LeadSource.EMAIL,
        status = LeadStatus.NEW,
        contactIdentifier = command.from,
        customerName = "Jan Kowalski",
        initialMessage = command.body,
        estimatedValue = 0L,
        requiresVerification = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
