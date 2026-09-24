package pl.detailing.crm.leads

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.domain.CommThreadKind
import pl.detailing.crm.comms.domain.CommThreadScreening
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.attachment.LeadAttachmentLinker
import pl.detailing.crm.leads.create.SoleUserResolver
import pl.detailing.crm.leads.formmail.ExtractedFormLead
import pl.detailing.crm.leads.formmail.FormMailExtractionEntity
import pl.detailing.crm.leads.formmail.FormMailExtractionRepository
import pl.detailing.crm.leads.formmail.FormMailExtractionService
import pl.detailing.crm.leads.formmail.FormMailLeadProcessor
import pl.detailing.crm.leads.formmail.FormMailProcessResult
import pl.detailing.crm.leads.formmail.FormMailSourceEntity
import pl.detailing.crm.leads.formmail.FormMailSourceRepository
import pl.detailing.crm.leads.formmail.FormSubmissionThreads
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.vehicle.VehicleCatalogMatcher
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Lead ze zgłoszenia z formularza po V157: dostaje wątek swojego zgłoszenia, kontakt
 * z Reply-To wygrywa z odczytem modelu, a spam i testy ze studia idą do „Odrzuconych".
 */
class FormMailLeadProcessorThreadTest {

    private val studioId = UUID.randomUUID()
    private val extractionService = mockk<FormMailExtractionService>()
    private val extractionRepository = mockk<FormMailExtractionRepository>()
    private val leadRepository = mockk<LeadRepository>()
    private val threadRepository = mockk<CommThreadRepository>()
    private val customerRepository = mockk<CustomerRepository>()
    private val soleUserResolver = mockk<SoleUserResolver>()
    private val catalogMatcher = mockk<VehicleCatalogMatcher>()
    private val userRepository = mockk<UserRepository>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val addressDirectory = object : MailAddressDirectory {
        override fun addressBook(studioId: UUID) = MailAddressBook(setOf("biuro@carslab.pl"), emptySet())
    }

    private val processor = FormMailLeadProcessor(
        extractionService, extractionRepository,
        mockk<LeadAttachmentLinker>(relaxed = true),
        mockk<FormMailSourceRepository>(relaxed = true),
        leadRepository, threadRepository, customerRepository,
        mockk<LeadStatusService>(relaxed = true),
        mockk<LeadTagService>(relaxed = true),
        mockk<LeadTagCatalogService>(relaxed = true),
        soleUserResolver, catalogMatcher, eventPublisher, transactionTemplate,
        FormSubmissionThreads(threadRepository, addressDirectory, userRepository, eventPublisher)
    )

    private val source = FormMailSourceEntity(studioId = studioId, senderEmail = "biuro@carslab.pl")
    private val savedLeads = mutableListOf<LeadEntity>()
    private val journal = mutableListOf<FormMailExtractionEntity>()

    private fun thread(kind: CommThreadKind, participant: String, leadId: UUID? = null) = CommThreadEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        accountId = UUID.randomUUID(),
        subjectNorm = "formularz kontaktowy - carslab - kontakt",
        subject = "Formularz kontaktowy - carslab - kontakt",
        participantEmail = participant,
        participantName = null,
        lastMessageAt = Instant.now(),
        lastDirection = CommDirection.INBOUND,
        lastSnippet = null,
        leadId = leadId,
        labelId = null,
        kind = kind,
        relayEmail = "biuro@carslab.pl"
    )

    private fun submission(thread: CommThreadEntity, replyTo: String?) = CommMessageEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        accountId = thread.accountId,
        threadId = thread.id,
        direction = CommDirection.INBOUND,
        folderKind = CommFolderKind.INBOX,
        messageIdHdr = "${UUID.randomUUID()}@carslab.pl",
        inReplyTo = null,
        referencesIds = null,
        fromEmail = "biuro@carslab.pl",
        fromName = "Carslab",
        toEmails = "biuro@carslab.pl",
        ccEmails = null,
        subject = thread.subject,
        sentAt = Instant.now(),
        bodyHtmlSafe = null,
        bodyText = BODY,
        bodyTextClean = BODY,
        imapUid = 1L,
        imapUidValidity = 1L,
        readSource = null,
        readAt = null,
        sendStatus = CommSendStatus.RECEIVED,
        replyToEmail = replyTo
    )

    private fun extraction(notAnInquiry: Boolean = false, email: String? = "grzechu.pawelec@gmail.com") =
        ExtractedFormLead(
            customerName = "Grzegorz Pawelec",
            email = email,
            phone = null,
            message = "Proszę o wycenę PPF na progi",
            service = null,
            vehicleBrand = "Toyota",
            vehicleModel = "RAV4",
            title = "Toyota RAV4 · folia PPF na progi",
            notAnInquiry = notAnInquiry,
            notAnInquiryReason = if (notAnInquiry) "Oferta wymiany linków" else null
        )

    @BeforeEach
    fun setUp() {
        every { extractionRepository.findByMessageId(any()) } returns null
        every { extractionRepository.save(any()) } answers { firstArg<FormMailExtractionEntity>().also { journal += it } }
        every { leadRepository.save(any()) } answers { firstArg<LeadEntity>().also { savedLeads += it } }
        every { leadRepository.findByThreadId(any()) } returns null
        every { threadRepository.save(any()) } answers { firstArg() }
        every { customerRepository.findActiveByStudioIdAndEmail(any(), any()) } returns null
        every { soleUserResolver.resolveForStudio(any()) } returns null
        every { userRepository.findByStudioId(studioId) } returns listOf(
            mockk<UserEntity> { every { email } returns "Mikolajblaszczak@o2.pl" }
        )
        coEvery { catalogMatcher.resolve(any(), any()) } returns VehicleCatalogMatcher.Match("Toyota", "RAV4")
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk(relaxed = true))
        }
    }

    private fun stubThread(thread: CommThreadEntity) {
        every { threadRepository.findById(thread.id) } returns Optional.of(thread)
    }

    @Test
    fun `lead dostaje watek zgloszenia, a kontakt z Reply-To wygrywa z odczytem`() {
        val thread = thread(CommThreadKind.FORM, "grzechu.pawelec@gmail.com").also(::stubThread)
        coEvery { extractionService.extract(any(), any()) } returns extraction(email = null)

        val result = processor.process(source, submission(thread, replyTo = "grzechu.pawelec@gmail.com"))

        val lead = savedLeads.single()
        assertTrue(result is FormMailProcessResult.Created)
        assertEquals(thread.id, lead.threadId)
        assertEquals("grzechu.pawelec@gmail.com", lead.contactIdentifier)
        assertEquals(lead.id, thread.leadId)
        assertEquals("Toyota RAV4 · folia PPF na progi", thread.title)
        assertEquals("Grzegorz Pawelec", thread.participantName)
    }

    @Test
    fun `robot bez Reply-To - watek przepiety na klienta z tresci`() {
        val thread = thread(CommThreadKind.FORM, "biuro@carslab.pl").also(::stubThread)
        coEvery { extractionService.extract(any(), any()) } returns extraction()

        processor.process(source, submission(thread, replyTo = null))

        assertEquals("grzechu.pawelec@gmail.com", thread.participantEmail)
    }

    @Test
    fun `duplikat zgloszenia nie zaklada drugiego leada`() {
        val existingLead = UUID.randomUUID()
        val thread = thread(CommThreadKind.FORM, "kuna199696@gmail.com", leadId = existingLead).also(::stubThread)
        coEvery { extractionService.extract(any(), any()) } returns extraction(email = null)

        val result = processor.process(source, submission(thread, replyTo = "kuna199696@gmail.com"))

        assertEquals(FormMailProcessResult.AlreadyProcessed(existingLead), result)
        assertTrue(savedLeads.isEmpty())
        assertEquals(FormMailLeadProcessor.STATUS_DUPLICATE, journal.single().status)
        assertEquals(existingLead, journal.single().leadId)
    }

    @Test
    fun `spam idzie do Odrzuconych, bez leada`() {
        val thread = thread(CommThreadKind.FORM, "aldenwins@outlook.com").also(::stubThread)
        coEvery { extractionService.extract(any(), any()) } returns extraction(notAnInquiry = true, email = null)

        val result = processor.process(source, submission(thread, replyTo = "aldenwins@outlook.com"))

        assertTrue(result is FormMailProcessResult.Rejected)
        assertTrue(savedLeads.isEmpty())
        assertEquals(CommThreadScreening.SPAM, thread.screening)
        assertEquals("Oferta wymiany linków", thread.screeningReason)
    }

    @Test
    fun `test formularza przez pracownika rozpoznany po adresie`() {
        val thread = thread(CommThreadKind.FORM, "mikolajblaszczak@o2.pl").also(::stubThread)
        coEvery { extractionService.extract(any(), any()) } returns extraction(email = null)

        val result = processor.process(source, submission(thread, replyTo = "mikolajblaszczak@o2.pl"))

        assertTrue(result is FormMailProcessResult.Rejected)
        assertTrue(savedLeads.isEmpty())
        assertEquals(CommThreadScreening.INTERNAL, thread.screening)
    }

    @Test
    fun `zgloszenie z wielkiego watku sprzed V157 dalej nie dostaje watku`() {
        val legacy = thread(CommThreadKind.DIRECT, "biuro@carslab.pl").also(::stubThread)
        coEvery { extractionService.extract(any(), any()) } returns extraction()

        processor.process(source, submission(legacy, replyTo = null))

        assertNull(savedLeads.single().threadId)
        assertNull(legacy.leadId)
        verify(exactly = 0) { threadRepository.save(legacy) }
    }

    private companion object {
        const val BODY = "Imię: Grzegorz Pawelec\nEmail: grzechu.pawelec@gmail.com\nSamochód: Toyota RAV-4"
    }
}
