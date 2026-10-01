package pl.detailing.crm.employee.leaverequest

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.create.CreateLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.create.CreateLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestSubmittedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestValidator
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestCounterRepository
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessionService
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.withdraw.WithdrawLeaveRequestHandler
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import java.time.LocalDate
import java.util.UUID

/**
 * Urlop dodany przez administratora (ON_BEHALF, kontrakt v2): ten sam szkic, te same reguły
 * i ten sam podpis WYSIWYS co w samoobsłudze — z tą różnicą, że pracownik podpisuje osobiście
 * na urządzeniu wprowadzającego, a szkic do chwili podpisu należy wyłącznie do niego.
 */
class OnBehalfLeaveRequestTest {

    private val studio = StudioId.random()
    private val adminUser = UserId.random()
    private val admin = LeaveRequestFixtures.principal(studio, adminUser, owner = true, name = "Anna Nowak")
    private val otherAdmin = LeaveRequestFixtures.principal(studio, owner = true, name = "Ewa Zielińska")
    private val employeeUser = UserId.random()
    private val employee = LeaveRequestFixtures.employee(studio, employeeUser)

    private val integrity = spyk(DocumentIntegrityService(mockk(relaxed = true), mockk(relaxed = true), 15))
    private val draftBytes = "on-behalf-draft-pdf".toByteArray()
    private val draftSha = integrity.sha256Hex(draftBytes)
    private val draft = LeaveRequestFixtures.request(
        studio, employee.id, employeeUser.value, status = LeaveRequestStatus.DRAFT, documentSha256 = draftSha,
        start = LocalDate.now().plusDays(20), origin = LeaveRequestOrigin.ON_BEHALF,
        createdBy = adminUser.value, createdByName = "Anna Nowak"
    )

    private val employeeRepository = mockk<EmployeeRepository>()
    private val leaveRequestRepository = mockk<LeaveRequestRepository>()
    private val employeeLeaveRepository = mockk<EmployeeLeaveRepository>()
    private val counterRepository = mockk<LeaveRequestCounterRepository>()
    private val documents = mockk<LeaveRequestDocumentService>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val auditService = mockk<AuditService>(relaxed = true)
    private val tx = RecordingTransactionManager()

    private val access = LeaveRequestAccess(employeeRepository, leaveRequestRepository)
    private val validator = LeaveRequestValidator(leaveRequestRepository, employeeLeaveRepository)
    private val sessions = LeaveSigningSessions(integrity)

    private val createHandler = CreateLeaveRequestHandler(
        employeeRepository, leaveRequestRepository, counterRepository, validator, access, documents, sessions, tx.template()
    )
    private val submitHandler = SubmitLeaveRequestHandler(
        access = access,
        employeeRepository = employeeRepository,
        leaveRequestRepository = leaveRequestRepository,
        validator = validator,
        sessions = sessions,
        documents = documents,
        signatureImageProcessor = SignatureImageProcessor(),
        auditService = auditService,
        eventPublisher = eventPublisher,
        transactionTemplate = tx.template()
    )
    private val withdrawHandler = WithdrawLeaveRequestHandler(access, leaveRequestRepository, employeeRepository, auditService, tx.template())
    private val sessionService = LeaveSigningSessionService(access, LeaveApprovalPolicy(mockk(relaxed = true)), sessions)

    private val storedKey = "${studio.value}/leave-requests/${draft.id}/employee-signed-abc.pdf"
    private val content = slot<LeaveRequestDocumentService.DraftContent>()
    private val saved = slot<LeaveRequestEntity>()

    init {
        every { employeeRepository.findByIdAndStudioId(employee.id, studio.value) } returns employee
        every { employeeRepository.findByIdAndStudioId(not(employee.id), any()) } returns null
        every { employeeRepository.findByStudioIdAndUserId(studio.value, employeeUser.value) } returns employee
        every { employeeRepository.lockForUpdate(employee.id, studio.value) } returns employee.id
        every { counterRepository.nextValue(studio.value, any()) } returns 12L
        every { leaveRequestRepository.save(capture(saved)) } answers { firstArg() }
        every { leaveRequestRepository.findByIdAndStudioId(draft.id, studio.value) } returns draft
        every { leaveRequestRepository.findOverlappingOfEmployee(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { leaveRequestRepository.findOnDemandOfEmployee(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { employeeLeaveRepository.findOverlappingOfEmployee(any(), any(), any(), any()) } returns emptyList()
        every { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { leaveRequestRepository.markOnBehalfDraftDiscarded(any(), any(), any(), any()) } returns 1
        every { integrity.consumeChallenge(any(), any()) } returns false
        every { integrity.consumeChallenge(any(), "good-challenge") } returns true
        every { integrity.issueChallenge(any(), any()) } returns "issued-challenge"
        coEvery { documents.createDraft(studio, any(), capture(content)) } returns
            LeaveRequestDocumentService.StoredPdf("${studio.value}/leave-requests/x/draft.pdf", draftSha, draftBytes)
        every { documents.download(draft.documentS3Key) } returns draftBytes
        coEvery { documents.storeEmployeeSigned(any(), any(), any(), any()) } returns
            LeaveRequestDocumentService.StoredPdf(storedKey, "c".repeat(64), byteArrayOf(1))
        coEvery { documents.deleteQuietly(any()) } just Runs
    }

    @AfterEach
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    private fun create(employeeId: UUID = employee.id, by: UserId = adminUser, start: LocalDate = LocalDate.now().plusDays(20)) =
        runBlocking {
            createHandler.handle(
                CreateLeaveRequestCommand(
                    studioId = studio,
                    userId = by,
                    userName = "Anna Nowak",
                    onBehalfOfEmployeeId = employeeId,
                    leaveType = "ANNUAL",
                    onDemand = false,
                    startDate = start,
                    endDate = start.plusDays(2),
                    reason = null
                )
            )
        }

    private fun signature(by: UserId = adminUser, sha: String = draftSha, challenge: String = "good-challenge") =
        SubmitLeaveRequestCommand(
            studioId = studio,
            userId = by,
            userName = "Anna Nowak",
            requestId = draft.id,
            signatureImageBase64 = LeaveRequestFixtures.signatureBase64(),
            documentSha256 = sha,
            challenge = challenge,
            declarationAccepted = true,
            ipAddress = "10.0.0.20",
            userAgent = "Mozilla/5.0 (iPad)"
        )

    // ── Utworzenie ──────────────────────────────────────────────────────────

    @Test
    fun `admin creates a draft on behalf of an employee, entered by the admin and owned by the employee`() {
        val result = create()

        val entity = saved.captured
        assertEquals(LeaveRequestStatus.DRAFT, entity.status)
        assertEquals(LeaveRequestOrigin.ON_BEHALF, entity.origin)
        assertEquals(employee.id, entity.employeeId)
        assertEquals(employeeUser.value, entity.employeeUserId)
        assertEquals(adminUser.value, entity.createdBy)
        assertEquals("Anna Nowak", entity.createdByName)
        assertEquals("WU/${LocalDate.now(java.time.ZoneId.of("Europe/Warsaw")).year}/0012", entity.number)
        assertEquals("Wprowadzony przez: Anna Nowak, podpisany osobiście", content.captured.submissionMode)
        assertEquals("issued-challenge", result.session.challenge)
        assertEquals(draftSha, result.session.documentSha256)
    }

    @Test
    fun `employee without an account can have a request entered for them`() {
        val noAccount = LeaveRequestFixtures.employee(studio, userId = null)
        every { employeeRepository.findByIdAndStudioId(noAccount.id, studio.value) } returns noAccount
        every { employeeRepository.lockForUpdate(noAccount.id, studio.value) } returns noAccount.id

        create(employeeId = noAccount.id)

        assertNull(saved.captured.employeeUserId)
        assertEquals(LeaveRequestOrigin.ON_BEHALF, saved.captured.origin)
    }

    @Test
    fun `entering a request for yourself is forbidden`() {
        val self = LeaveRequestFixtures.employee(studio, adminUser)
        every { employeeRepository.findByIdAndStudioId(self.id, studio.value) } returns self

        val e = assertThrows<ForbiddenException> { create(employeeId = self.id) }

        assertEquals("Własny wniosek złóż w zakładce Urlop", e.message)
        verify(exactly = 0) { counterRepository.nextValue(any(), any()) }
        verify(exactly = 0) { leaveRequestRepository.save(any()) }
    }

    @Test
    fun `employee of another studio is not found`() {
        val foreign = LeaveRequestFixtures.employee(StudioId.random(), UserId.random())

        assertThrows<NotFoundException> { create(employeeId = foreign.id) }

        verify(exactly = 0) { counterRepository.nextValue(any(), any()) }
        verify(exactly = 0) { leaveRequestRepository.save(any()) }
    }

    @Test
    fun `self-service rules apply and the message speaks about the employee`() {
        every { leaveRequestRepository.findOverlappingOfEmployee(studio.value, employee.id, any(), any(), any(), any()) } returns
            listOf(LeaveRequestFixtures.request(studio, employee.id, employeeUser.value, status = LeaveRequestStatus.APPROVED))

        val e = assertThrows<ValidationException> { create() }

        assertEquals("startDate", e.field)
        assertTrue(e.message!!.startsWith("W tym terminie pracownik ma już wniosek"), e.message)
        assertThrows<ValidationException> { create(start = LocalDate.now().minusDays(1)) }
        verify(exactly = 0) { leaveRequestRepository.save(any()) }
    }

    // ── Podpis osobisty ─────────────────────────────────────────────────────

    @Test
    fun `employee signature in person makes the request pending, signed IN_PERSON, without a push to the author`() {
        runBlocking { submitHandler.handleInPerson(signature()) }

        verify(exactly = 1) {
            leaveRequestRepository.markSubmitted(
                draft.id, studio.value, any(), LeaveSignatureMethod.IN_PERSON, storedKey, "c".repeat(64),
                "10.0.0.20", "Mozilla/5.0 (iPad)"
            )
        }
        verify(exactly = 1) {
            eventPublisher.publishEvent(match<Any> {
                it is LeaveRequestSubmittedEvent && it.createdByUserId == adminUser && it.employeeUserId == employeeUser
            })
        }
        verify(exactly = 1) {
            auditService.recordSync(match<AuditEvent> {
                it.metadata["signatureMethod"] == "IN_PERSON" && it.metadata["origin"] == "ON_BEHALF"
            })
        }
        assertEquals(1, tx.commits)
    }

    @Test
    fun `hash that differs from the stored draft is rejected and nothing is stored`() {
        assertThrows<ConflictException> { runBlocking { submitHandler.handleInPerson(signature(sha = "0".repeat(64))) } }

        coVerify(exactly = 0) { documents.storeEmployeeSigned(any(), any(), any(), any()) }
        verify(exactly = 0) { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `file changed in storage is rejected even with a matching client hash`() {
        every { documents.download(draft.documentS3Key) } returns "tampered".toByteArray()

        assertThrows<ConflictException> { runBlocking { submitHandler.handleInPerson(signature()) } }
        coVerify(exactly = 0) { documents.storeEmployeeSigned(any(), any(), any(), any()) }
    }

    @Test
    fun `consumed challenge is rejected before the document is read`() {
        assertThrows<ConflictException> { runBlocking { submitHandler.handleInPerson(signature(challenge = "replayed")) } }

        verify(exactly = 0) { documents.download(any()) }
        verify(exactly = 0) { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `another administrator cannot take the in-person signature`() {
        assertThrows<NotFoundException> { runBlocking { submitHandler.handleInPerson(signature(by = otherAdmin.userId)) } }

        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `already signed request is not signed again`() {
        every { leaveRequestRepository.findByIdAndStudioId(draft.id, studio.value) } returns LeaveRequestFixtures.request(
            studio, employee.id, employeeUser.value, status = LeaveRequestStatus.PENDING, id = draft.id,
            origin = LeaveRequestOrigin.ON_BEHALF, createdBy = adminUser.value
        )

        assertThrows<ConflictException> { runBlocking { submitHandler.handleInPerson(signature()) } }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `employee cannot sign the on-behalf draft from self-service`() {
        assertThrows<NotFoundException> { runBlocking { submitHandler.handle(signature(by = employeeUser)) } }
        assertThrows<NotFoundException> { sessionService.forEmployee(LeaveRequestFixtures.principal(studio, employeeUser), draft.id) }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `signing session for the draft goes only to the administrator who entered it`() {
        assertEquals("issued-challenge", sessionService.forEmployeeInPerson(admin, draft.id).challenge)
        assertThrows<NotFoundException> { sessionService.forEmployeeInPerson(otherAdmin, draft.id) }
    }

    // ── Porzucenie ──────────────────────────────────────────────────────────

    @Test
    fun `author discards the draft`() {
        runBlocking { withdrawHandler.discardOnBehalf(admin, draft.id) }

        verify(exactly = 1) { leaveRequestRepository.markOnBehalfDraftDiscarded(draft.id, studio.value, any(), adminUser.value) }
        assertEquals(1, tx.commits)
    }

    @Test
    fun `another administrator cannot discard the draft`() {
        assertThrows<NotFoundException> { runBlocking { withdrawHandler.discardOnBehalf(otherAdmin, draft.id) } }
        verify(exactly = 0) { leaveRequestRepository.markOnBehalfDraftDiscarded(any(), any(), any(), any()) }
    }

    @Test
    fun `signed request cannot be discarded`() {
        every { leaveRequestRepository.findByIdAndStudioId(draft.id, studio.value) } returns LeaveRequestFixtures.request(
            studio, employee.id, employeeUser.value, status = LeaveRequestStatus.PENDING, id = draft.id,
            origin = LeaveRequestOrigin.ON_BEHALF, createdBy = adminUser.value
        )

        assertThrows<ConflictException> { runBlocking { withdrawHandler.discardOnBehalf(admin, draft.id) } }
        verify(exactly = 0) { leaveRequestRepository.markOnBehalfDraftDiscarded(any(), any(), any(), any()) }
    }

    @Test
    fun `losing the race to the employee signature is a conflict, not a silent withdrawal`() {
        every { leaveRequestRepository.markOnBehalfDraftDiscarded(any(), any(), any(), any()) } returns 0

        assertThrows<ConflictException> { runBlocking { withdrawHandler.discardOnBehalf(admin, draft.id) } }
        assertEquals(1, tx.rollbacks)
    }

    // ── Dokument do podpisu ─────────────────────────────────────────────────

    private val pdfResponses = mockk<LeaveRequestPdfResponses>()
    private val controller = LeaveRequestApprovalController(
        queries = mockk(relaxed = true),
        access = access,
        presenter = mockk(relaxed = true),
        pdfResponses = pdfResponses,
        sessions = sessionService,
        decideHandler = mockk(relaxed = true),
        cancelHandler = mockk(relaxed = true),
        createHandler = createHandler,
        submitHandler = submitHandler,
        withdrawHandler = withdrawHandler,
        trustedProxies = ""
    )

    @Test
    fun `document of an on-behalf draft is the unsigned version, only for its author`() {
        coEvery { pdfResponses.forSigning(any(), any(), any()) } returns ResponseEntity.ok(draftBytes)

        SecurityContextHolder.getContext().authentication = admin
        controller.document(draft.id)
        coVerify(exactly = 1) { pdfResponses.forSigning(draft, draft.documentS3Key, draftSha) }

        SecurityContextHolder.getContext().authentication = otherAdmin
        assertThrows<NotFoundException> { controller.document(draft.id) }
        coVerify(exactly = 1) { pdfResponses.forSigning(any(), any(), any()) }
    }

    @Test
    fun `after the employee signature the author gets the employee-signed version like any approver`() {
        val pending = LeaveRequestFixtures.request(
            studio, employee.id, employeeUser.value, status = LeaveRequestStatus.PENDING, id = draft.id,
            origin = LeaveRequestOrigin.ON_BEHALF, createdBy = adminUser.value,
            employeeSignatureMethod = LeaveSignatureMethod.IN_PERSON
        )
        every { leaveRequestRepository.findByIdAndStudioId(draft.id, studio.value) } returns pending
        coEvery { pdfResponses.forSigning(any(), any(), any()) } returns ResponseEntity.ok(byteArrayOf(2))

        SecurityContextHolder.getContext().authentication = admin
        controller.document(draft.id)

        coVerify(exactly = 1) { pdfResponses.forSigning(pending, pending.employeeSignedPdfS3Key, pending.employeeSignedSha256) }
    }
}
