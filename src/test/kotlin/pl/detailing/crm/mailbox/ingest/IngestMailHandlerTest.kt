package pl.detailing.crm.mailbox.ingest

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.inbound.email.domain.EmailClassificationResult
import pl.detailing.crm.inbound.email.domain.EmailLeadClassifier
import pl.detailing.crm.leads.comments.AddLeadCommentCommand
import pl.detailing.crm.leads.comments.LeadCommentHandler
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.create.CreateLeadResult
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadHandler
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.mailbox.domain.EmailCleaningService
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.MailSendStatus
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import pl.detailing.crm.mailbox.domain.RawIncomingEmail
import pl.detailing.crm.mailbox.domain.ThreadingService
import pl.detailing.crm.mailbox.infrastructure.MailMessageEntity
import pl.detailing.crm.mailbox.infrastructure.MailMessageRepository
import pl.detailing.crm.mailbox.infrastructure.MailSenderRuleEntity
import pl.detailing.crm.mailbox.infrastructure.MailSenderRuleRepository
import pl.detailing.crm.mailbox.infrastructure.MailThreadEntity
import pl.detailing.crm.mailbox.infrastructure.MailThreadRepository
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID

class IngestMailHandlerTest {

    private val threadingService = mockk<ThreadingService>()
    private val cleaningService = EmailCleaningService()
    private val classifier = mockk<EmailLeadClassifier>()
    private val threadRepository = mockk<MailThreadRepository>(relaxed = true)
    private val messageRepository = mockk<MailMessageRepository>()
    private val senderRuleRepository = mockk<MailSenderRuleRepository>()
    private val leadRepository = mockk<LeadRepository>(relaxed = true)
    private val createLeadHandler = mockk<CreateLeadHandler>()
    private val analyzeLeadHandler = mockk<AnalyzeLeadHandler>(relaxed = true)
    private val leadCommentHandler = mockk<LeadCommentHandler>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)

    private val handler = IngestMailHandler(
        threadingService, cleaningService, classifier, threadRepository, messageRepository,
        senderRuleRepository, leadRepository, createLeadHandler, analyzeLeadHandler,
        leadCommentHandler, eventPublisher, auditService
    )

    private val studioId = UUID.randomUUID()

    /**
     * The behaviour the old "does this address have an open lead" heuristic got wrong:
     * a reply must land on the conversation it answers, decided by headers, and must not
     * spawn a second lead.
     */
    @Test
    fun `reply on a thread that already has a lead is appended, not turned into a new lead`() {
        val leadId = UUID.randomUUID()
        val thread = thread(leadId = leadId)
        givenNewMessage(thread)
        every { leadRepository.findById(leadId) } returns Optional.of(lead(leadId))
        every { leadRepository.save(any()) } answers { firstArg() }

        val result = runBlocking { handler.handle(studioId, null, inboundEmail(inReplyTo = "parent@x.pl")) }

        assertEquals(MailIngestResult.ReplyAppended(leadId), result)
        val comment = slot<AddLeadCommentCommand>()
        coVerify(exactly = 1) { leadCommentHandler.addComment(capture(comment)) }
        assertTrue(comment.captured.content.contains("Klient odpowiedział na maila"))
        coVerify(exactly = 0) { createLeadHandler.handle(any()) }
        coVerify(exactly = 0) { classifier.classify(any(), any(), any()) }
    }

    @Test
    fun `an inquiry on a fresh thread creates a lead and links it to the thread`() {
        val thread = thread(leadId = null)
        givenNewMessage(thread)
        every { senderRuleRepository.findByStudioIdAndPatternIn(studioId, any()) } returns emptyList()
        coEvery { classifier.classify(any(), any(), any()) } returns EmailClassificationResult.LeadDetected(
            extractedName = "Jan Kowalski",
            summary = "Pyta o ceramikę",
            vehicleMake = "BMW",
            vehicleModel = "X5",
            vehicleYear = 2021,
            requestedServices = listOf("powłoka ceramiczna")
        )
        val newLeadId = LeadId(UUID.randomUUID())
        coEvery { createLeadHandler.handle(any()) } returns createResult(newLeadId)

        val result = runBlocking { handler.handle(studioId, null, inboundEmail()) }

        assertEquals(MailIngestResult.LeadCreated(newLeadId.value), result)
        assertEquals(newLeadId.value, thread.leadId)
        assertEquals(MailThreadClassification.INQUIRY, thread.classification)

        val command = slot<CreateLeadCommand>()
        coVerify(exactly = 1) { createLeadHandler.handle(capture(command)) }
        assertEquals(LeadSource.EMAIL, command.captured.source)
        assertEquals("jan@wp.pl", command.captured.contactIdentifier)
        assertEquals("Jan Kowalski", command.captured.customerName)
    }

    @Test
    fun `newsletter headers are rejected before the model is called`() {
        val thread = thread(leadId = null)
        givenNewMessage(thread)

        val result = runBlocking {
            handler.handle(
                studioId, null,
                inboundEmail(headers = mapOf("list-unsubscribe" to "<mailto:stop@newsletter.pl>"))
            )
        }

        assertEquals(MailIngestResult.Ignored(MailThreadClassification.OTHER.name), result)
        coVerify(exactly = 0) { classifier.classify(any(), any(), any()) }
        coVerify(exactly = 0) { createLeadHandler.handle(any()) }
    }

    @Test
    fun `a learned sender rule short-circuits classification`() {
        val thread = thread(leadId = null)
        givenNewMessage(thread)
        every { senderRuleRepository.findByStudioIdAndPatternIn(studioId, any()) } returns listOf(
            MailSenderRuleEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                pattern = "jan@wp.pl",
                classification = MailThreadClassification.OTHER
            )
        )

        val result = runBlocking { handler.handle(studioId, null, inboundEmail()) }

        assertEquals(MailIngestResult.Ignored(MailThreadClassification.OTHER.name), result)
        coVerify(exactly = 0) { classifier.classify(any(), any(), any()) }
    }

    @Test
    fun `an already classified thread inherits its verdict without another model call`() {
        val thread = thread(leadId = null, classification = MailThreadClassification.OTHER)
        givenNewMessage(thread)

        val result = runBlocking { handler.handle(studioId, null, inboundEmail()) }

        assertEquals(MailIngestResult.Ignored(MailThreadClassification.OTHER.name), result)
        coVerify(exactly = 0) { classifier.classify(any(), any(), any()) }
        verify(exactly = 0) { senderRuleRepository.findByStudioIdAndPatternIn(any(), any()) }
    }

    @Test
    fun `a message already stored is not duplicated`() {
        val stored = storedMessage(UUID.randomUUID())
        every { messageRepository.findByStudioIdAndMessageId(studioId, "dup@x.pl") } returns stored
        every { messageRepository.save(any()) } answers { firstArg() }

        val result = runBlocking {
            handler.handle(studioId, null, inboundEmail(messageId = "dup@x.pl", providerUid = "42"))
        }

        assertEquals(MailIngestResult.Duplicate, result)
        assertEquals("42", stored.providerUid)
        coVerify(exactly = 0) { createLeadHandler.handle(any()) }
    }

    @Test
    fun `our own outgoing copy joins the thread without touching the lead`() {
        val thread = thread(leadId = UUID.randomUUID())
        givenNewMessage(thread)

        val result = runBlocking {
            handler.handle(
                studioId, null,
                inboundEmail().copy(direction = MailDirection.OUTBOUND, toEmails = listOf("jan@wp.pl"))
            )
        }

        assertEquals(MailIngestResult.Stored, result)
        coVerify(exactly = 0) { leadCommentHandler.addComment(any()) }
        coVerify(exactly = 0) { createLeadHandler.handle(any()) }
    }

    private fun givenNewMessage(thread: MailThreadEntity) {
        every { messageRepository.findByStudioIdAndMessageId(studioId, any()) } returns null
        every { messageRepository.save(any()) } answers { firstArg() }
        every { threadRepository.save(any()) } answers { firstArg() }
        every {
            threadingService.resolveThread(any(), any(), any(), any(), any(), any(), any(), any())
        } returns thread
        every { threadingService.touch(any(), any(), any()) } answers { firstArg() }
    }

    private fun inboundEmail(
        messageId: String? = "new@x.pl",
        inReplyTo: String? = null,
        providerUid: String? = null,
        headers: Map<String, String> = emptyMap()
    ) = RawIncomingEmail(
        messageId = messageId,
        inReplyTo = inReplyTo,
        references = emptyList(),
        fromEmail = "jan@wp.pl",
        fromName = "Jan",
        toEmails = emptyList(),
        subject = "Wycena ceramiki",
        sentAt = Instant.now(),
        bodyHtml = null,
        bodyText = "Dzień dobry, ile kosztuje powłoka ceramiczna na BMW X5?",
        providerUid = providerUid,
        direction = MailDirection.INBOUND,
        headers = headers
    )

    private fun thread(
        leadId: UUID?,
        classification: MailThreadClassification = MailThreadClassification.PENDING
    ) = MailThreadEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        mailAccountId = null,
        leadId = leadId,
        subjectNorm = "wycena ceramiki",
        externalThreadKey = null,
        classification = classification,
        lastMessageAt = Instant.now(),
        lastDirection = null
    )

    private fun storedMessage(threadId: UUID) = MailMessageEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        threadId = threadId,
        mailAccountId = null,
        direction = MailDirection.INBOUND,
        messageId = "dup@x.pl",
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

    private fun lead(id: UUID) = LeadEntity(
        id = id,
        studioId = studioId,
        source = LeadSource.EMAIL,
        status = LeadStatus.NEW,
        contactIdentifier = "jan@wp.pl",
        customerName = "Jan Kowalski",
        initialMessage = "Pierwsza wiadomość",
        estimatedValue = 0L,
        requiresVerification = false,
        vehicleBrand = null,
        vehicleModel = null,
        customerId = null,
        appointmentId = null,
        visitId = null,
        assignedUserId = null,
        assignedUserName = null,
        lostReason = null,
        stagnantAlertSentAt = null,
        newActivityAt = null
    )

    private fun createResult(leadId: LeadId) = CreateLeadResult(
        leadId = leadId,
        source = LeadSource.EMAIL,
        status = LeadStatus.NEW,
        contactIdentifier = "jan@wp.pl",
        customerName = "Jan Kowalski",
        initialMessage = "Dzień dobry",
        estimatedValue = 0L,
        requiresVerification = false,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
