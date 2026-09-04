package pl.detailing.crm.leads.classification

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.create.SoleUserResolver
import pl.detailing.crm.leads.formmail.FormMailLeadProcessor
import pl.detailing.crm.leads.formmail.FormMailProcessResult
import pl.detailing.crm.leads.formmail.FormMailSourceEntity
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Co wolno zrobić z werdyktem modelu.
 *
 * Rozdzielenie „model powiedział LEAD” od „powstaje lead” jest tu całą treścią: między
 * jednym a drugim stoi próg pewności, a poniżej progu ma NIE powstać nic. Test pilnuje
 * też, żeby każdy zakończony przebieg zostawił ślad w dzienniku — bez niego kolejny
 * sync tej samej skrzynki zapłaciłby za tę samą klasyfikację jeszcze raz.
 */
class AutoLeadProcessorTest {

    private val classifier = mockk<LeadMessageClassifier>()
    private val classificationRepository = mockk<LeadMessageClassificationRepository>()
    private val rateLimiter = mockk<LeadClassificationRateLimiter>()
    private val formMailLeadProcessor = mockk<FormMailLeadProcessor>()
    private val leadRepository = mockk<LeadRepository>(relaxed = true)
    private val threadRepository = mockk<CommThreadRepository>(relaxed = true)
    private val customerRepository = mockk<CustomerRepository>()
    private val statusService = mockk<LeadStatusService>(relaxed = true)
    private val soleUserResolver = mockk<SoleUserResolver>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val transactionTemplate = mockk<TransactionTemplate>()

    private val studioId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()
    private val messageId = UUID.randomUUID()

    private val savedEntries = mutableListOf<LeadMessageClassificationEntity>()

    private fun processor(minConfidence: Double = 0.7) = AutoLeadProcessor(
        classifier, classificationRepository, rateLimiter, formMailLeadProcessor,
        leadRepository, threadRepository, customerRepository, statusService,
        soleUserResolver, eventPublisher, transactionTemplate, minConfidence
    )

    private fun thread() = CommThreadEntity(
        id = threadId,
        studioId = studioId,
        accountId = UUID.randomUUID(),
        subjectNorm = "wycena",
        subject = "Wycena",
        participantEmail = "klient@example.com",
        participantName = "Jan Kowalski",
        lastMessageAt = Instant.now(),
        lastDirection = CommDirection.INBOUND,
        lastSnippet = "Ile kosztuje…",
        leadId = null,
        labelId = null
    )

    private fun message(body: String? = "Ile kosztuje oklejenie BMW M3?") = CommMessageEntity(
        id = messageId,
        studioId = studioId,
        accountId = UUID.randomUUID(),
        threadId = threadId,
        direction = CommDirection.INBOUND,
        folderKind = CommFolderKind.INBOX,
        messageIdHdr = "msg-1",
        inReplyTo = null,
        referencesIds = null,
        fromEmail = "klient@example.com",
        fromName = "Jan Kowalski",
        toEmails = "studio@example.com",
        ccEmails = null,
        subject = "Wycena",
        sentAt = Instant.now(),
        bodyHtmlSafe = null,
        bodyText = body,
        bodyTextClean = body,
        imapUid = 1L,
        imapUidValidity = 1L,
        readSource = null,
        readAt = null,
        sendStatus = CommSendStatus.RECEIVED
    )

    @BeforeEach
    fun setUp() {
        savedEntries.clear()
        every { classificationRepository.findByMessageId(messageId) } returns null
        every { rateLimiter.tryConsume(studioId) } returns true
        every { classifier.modelName() } returns "gpt-4o-mini"
        every { customerRepository.findActiveByStudioIdAndEmail(any(), any()) } returns null
        every { soleUserResolver.resolveForStudio(studioId) } returns null
        every { threadRepository.findById(threadId) } returns Optional.of(thread())
        // JpaRepository.save jest generyczne (<S : T> save(S): S) — relaxed mock nie ma
        // z czego wywnioskować S i oddaje goły Object, który wywala się na rzutowaniu.
        every { threadRepository.save(any()) } answers { firstArg() }
        every { leadRepository.findByThreadId(threadId) } returns null

        val entry = slot<LeadMessageClassificationEntity>()
        every { classificationRepository.save(capture(entry)) } answers {
            savedEntries += entry.captured
            entry.captured
        }

        // TransactionTemplate wykonuje ciało od razu — testujemy decyzje, nie transakcje.
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk(relaxed = true))
        }
    }

    private fun stubVerdict(verdict: LeadClassificationVerdict, confidence: Double) {
        coEvery { classifier.classify(any(), any()) } returns
            LeadClassification(verdict, confidence, "uzasadnienie")
    }

    @Test
    fun `pewny lead tworzy leada z watkiem`() {
        stubVerdict(LeadClassificationVerdict.LEAD, 0.95)
        val lead = slot<LeadEntity>()
        every { leadRepository.save(capture(lead)) } answers { lead.captured }

        val result = processor().process(message(), thread(), null)

        assertInstanceOf(AutoLeadResult.Created::class.java, result)
        assertEquals(LeadSource.EMAIL, lead.captured.source)
        assertEquals(LeadStatus.NEW, lead.captured.status)
        assertEquals("klient@example.com", lead.captured.contactIdentifier)
        assertEquals(threadId, lead.captured.threadId, "Korespondencja ma być historią leada")
        assertEquals(AutoLeadProcessor.STATUS_CREATED, savedEntries.single().status)
    }

    @Test
    fun `lead ponizej progu pewnosci nie powstaje`() {
        stubVerdict(LeadClassificationVerdict.LEAD, 0.55)

        val result = processor(minConfidence = 0.7).process(message(), thread(), null)

        assertInstanceOf(AutoLeadResult.Rejected::class.java, result)
        verify(exactly = 0) { leadRepository.save(any()) }
        // Werdykt i pewność zostają w dzienniku — po nich stroi się próg.
        val entry = savedEntries.single()
        assertEquals(AutoLeadProcessor.STATUS_REJECTED, entry.status)
        assertEquals("LEAD", entry.verdict)
        assertEquals(0, entry.confidence!!.compareTo(java.math.BigDecimal("0.55")))
    }

    @Test
    fun `pewnosc dokladnie na progu wystarcza`() {
        stubVerdict(LeadClassificationVerdict.LEAD, 0.7)
        every { leadRepository.save(any()) } answers { firstArg() }

        assertInstanceOf(
            AutoLeadResult.Created::class.java,
            processor(minConfidence = 0.7).process(message(), thread(), null)
        )
    }

    @Test
    fun `nie-lead zostawia slad z uzasadnieniem, ale nie tworzy leada`() {
        stubVerdict(LeadClassificationVerdict.NOT_LEAD, 0.98)

        val result = processor().process(message(), thread(), null)

        assertInstanceOf(AutoLeadResult.Rejected::class.java, result)
        verify(exactly = 0) { leadRepository.save(any()) }
        val entry = savedEntries.single()
        assertEquals("NOT_LEAD", entry.verdict)
        assertEquals("uzasadnienie", entry.reasoning, "Bez uzasadnienia nie da się odpowiedzieć, czemu mail przepadł")
        assertEquals("gpt-4o-mini", entry.model, "Bez nazwy modelu nie da się porównać skuteczności po podmianie")
    }

    @Test
    fun `awaria modelu to FAILED, nie ciche odrzucenie`() {
        coEvery { classifier.classify(any(), any()) } returns null

        val result = processor().process(message(), thread(), null)

        assertInstanceOf(AutoLeadResult.Failed::class.java, result)
        val entry = savedEntries.single()
        assertEquals(AutoLeadProcessor.STATUS_FAILED, entry.status)
        assertNull(entry.verdict, "„Nie wiemy” to nie to samo co „to nie jest lead”")
    }

    @Test
    fun `wyczerpany limit pomija wiadomosc bez pytania modelu`() {
        every { rateLimiter.tryConsume(studioId) } returns false

        val result = processor().process(message(), thread(), null)

        assertInstanceOf(AutoLeadResult.Skipped::class.java, result)
        assertEquals(AutoLeadProcessor.STATUS_SKIPPED, savedEntries.single().status)
    }

    @Test
    fun `wiadomosc bez tresci nie idzie do modelu`() {
        val result = processor().process(message(body = null), thread(), null)

        assertInstanceOf(AutoLeadResult.Skipped::class.java, result)
        verify(exactly = 0) { rateLimiter.tryConsume(any()) }
    }

    @Test
    fun `znany dziennikowi mail nie jest klasyfikowany drugi raz`() {
        val existingLead = UUID.randomUUID()
        every { classificationRepository.findByMessageId(messageId) } returns
            LeadMessageClassificationEntity(
                studioId = studioId, messageId = messageId, threadId = threadId,
                status = AutoLeadProcessor.STATUS_CREATED, leadId = existingLead
            )

        val result = processor().process(message(), thread(), null)

        assertEquals(AutoLeadResult.AlreadyProcessed(existingLead), result)
        verify(exactly = 0) { rateLimiter.tryConsume(any()) }
    }

    @Test
    fun `watek oznaczony recznie w trakcie klasyfikacji nie dostaje drugiego leada`() {
        // Rozmowa z modelem trwa sekundy — w tym czasie ktoś mógł kliknąć „Oznacz jako lead”.
        stubVerdict(LeadClassificationVerdict.LEAD, 0.95)
        every { threadRepository.findById(threadId) } returns
            Optional.of(thread().apply { leadId = UUID.randomUUID() })

        val result = processor().process(message(), thread(), null)

        assertInstanceOf(AutoLeadResult.Skipped::class.java, result)
        verify(exactly = 0) { leadRepository.save(any()) }
    }

    @Test
    fun `mail z formularza buduje leada sciezka form-mail`() {
        // Nadawcą jest robot (wordpress@), a kontakt klienta stoi w TREŚCI — tylko
        // FormMailLeadProcessor umie go stamtąd wyjąć.
        stubVerdict(LeadClassificationVerdict.LEAD, 0.95)
        val source = FormMailSourceEntity(studioId = studioId, senderEmail = "wordpress@studio.pl")
        val leadId = UUID.randomUUID()
        every { formMailLeadProcessor.process(source, any()) } returns FormMailProcessResult.Created(leadId)

        val result = processor().process(message(), thread(), source)

        assertEquals(AutoLeadResult.Created(leadId), result)
        verify(exactly = 0) { leadRepository.save(any()) }
        assertEquals(leadId, savedEntries.single().leadId)
    }

    @Test
    fun `nie-lead z formularza nie uruchamia budowy leada`() {
        stubVerdict(LeadClassificationVerdict.NOT_LEAD, 0.9)
        val source = FormMailSourceEntity(studioId = studioId, senderEmail = "wordpress@studio.pl")

        processor().process(message(), thread(), source)

        verify(exactly = 0) { formMailLeadProcessor.process(any(), any()) }
    }

    @Test
    fun `mail z formularza znany tamtemu dziennikowi bez leada nie wywraca przebiegu`() {
        // FormMailProcessResult.AlreadyProcessed niesie leadId = NULL, gdy poprzedni
        // przebieg skończył się odrzuceniem (formularz bez kontaktu klienta), a nie
        // leadem. Wnioskowanie „AlreadyProcessed znaczy, że lead jest" wywracało tu
        // cały przebieg wyjątkiem — i to na ścieżce, która zdarza się naprawdę.
        stubVerdict(LeadClassificationVerdict.LEAD, 0.95)
        val source = FormMailSourceEntity(studioId = studioId, senderEmail = "wordpress@studio.pl")
        every { formMailLeadProcessor.process(source, any()) } returns
            FormMailProcessResult.AlreadyProcessed(null)

        val result = processor().process(message(), thread(), source)

        assertEquals(AutoLeadResult.AlreadyProcessed(null), result)
        assertNull(savedEntries.single().leadId)
    }

    @Test
    fun `odrzucenie po stronie form-maila trafia do dziennika`() {
        // Formularz bez kontaktu klienta to nie awaria modelu — model miał rację,
        // że to zapytanie, tylko nie ma jak na nie odpisać.
        stubVerdict(LeadClassificationVerdict.LEAD, 0.95)
        val source = FormMailSourceEntity(studioId = studioId, senderEmail = "wordpress@studio.pl")
        every { formMailLeadProcessor.process(source, any()) } returns
            FormMailProcessResult.Rejected("Brak kontaktu w treści")

        val result = processor().process(message(), thread(), source)

        assertInstanceOf(AutoLeadResult.Rejected::class.java, result)
        assertEquals("Brak kontaktu w treści", savedEntries.single().reason)
    }
}
