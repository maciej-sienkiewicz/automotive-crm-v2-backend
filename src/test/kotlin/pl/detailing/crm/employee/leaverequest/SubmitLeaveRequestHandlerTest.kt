package pl.detailing.crm.employee.leaverequest

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestSubmittedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestValidator
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestHandler
import pl.detailing.crm.shared.ConflictException
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
 * Złożenie wniosku: pracownik podpisuje dokładnie te bajty, których skrót dostał, jednym
 * tokenem, raz. Każde odstępstwo to 409 i żadnego zapisu.
 */
class SubmitLeaveRequestHandlerTest {

    private val studio = StudioId.random()
    private val userId = UserId.random()
    private val employee = LeaveRequestFixtures.employee(studio, userId)

    private val integrity = spyk(DocumentIntegrityService(mockk(relaxed = true), mockk(relaxed = true), 15))
    private val draftBytes = "draft-pdf".toByteArray()
    private val draftSha = integrity.sha256Hex(draftBytes)
    private val request = LeaveRequestFixtures.request(
        studio, employee.id, userId.value, status = LeaveRequestStatus.DRAFT, documentSha256 = draftSha,
        start = LocalDate.now().plusDays(20)
    )

    private val employeeRepository = mockk<EmployeeRepository>()
    private val leaveRequestRepository = mockk<LeaveRequestRepository>()
    private val employeeLeaveRepository = mockk<EmployeeLeaveRepository>()
    private val documents = mockk<LeaveRequestDocumentService>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val tx = RecordingTransactionManager()

    private val handler = SubmitLeaveRequestHandler(
        access = LeaveRequestAccess(employeeRepository, leaveRequestRepository),
        employeeRepository = employeeRepository,
        leaveRequestRepository = leaveRequestRepository,
        validator = LeaveRequestValidator(leaveRequestRepository, employeeLeaveRepository),
        sessions = LeaveSigningSessions(integrity),
        documents = documents,
        signatureImageProcessor = SignatureImageProcessor(),
        auditService = mockk<AuditService>(relaxed = true),
        eventPublisher = eventPublisher,
        transactionTemplate = tx.template()
    )

    private val storedKey = "${studio.value}/leave-requests/${request.id}/employee-signed-abc.pdf"

    init {
        every { employeeRepository.findByStudioIdAndUserId(studio.value, userId.value) } returns employee
        every { employeeRepository.lockForUpdate(employee.id, studio.value) } returns employee.id
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns request
        every { leaveRequestRepository.findOverlappingOfEmployee(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { leaveRequestRepository.findOnDemandOfEmployee(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { employeeLeaveRepository.findOverlappingOfEmployee(any(), any(), any(), any()) } returns emptyList()
        every { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { integrity.consumeChallenge(any(), any()) } returns false
        every { integrity.consumeChallenge(any(), "good-challenge") } returns true
        every { documents.download(request.documentS3Key) } returns draftBytes
        coEvery { documents.storeEmployeeSigned(any(), any(), any(), any()) } returns
            LeaveRequestDocumentService.StoredPdf(storedKey, "c".repeat(64), byteArrayOf(1))
        coEvery { documents.deleteQuietly(any()) } just Runs
    }

    private fun command(sha: String = draftSha, challenge: String = "good-challenge", declaration: Boolean = true) =
        SubmitLeaveRequestCommand(
            studioId = studio,
            userId = userId,
            userName = "Jan Kowalski",
            requestId = request.id,
            signatureImageBase64 = LeaveRequestFixtures.signatureBase64(),
            documentSha256 = sha,
            challenge = challenge,
            declarationAccepted = declaration,
            ipAddress = "10.0.0.7",
            userAgent = "Mozilla/5.0 (iPhone)"
        )

    @Test
    fun `signed draft becomes pending with the employee-signed file and notifies approvers after commit`() {
        runBlocking { handler.handle(command()) }

        verify(exactly = 1) {
            leaveRequestRepository.markSubmitted(
                request.id, studio.value, any(), LeaveSignatureMethod.DEVICE_DRAWN, storedKey, "c".repeat(64),
                "10.0.0.7", "Mozilla/5.0 (iPhone)"
            )
        }
        verify(exactly = 1) { eventPublisher.publishEvent(match<Any> { it is LeaveRequestSubmittedEvent && it.requestId == request.id }) }
        assertEquals(1, tx.commits)
    }

    @Test
    fun `hash from the client that differs from the draft is rejected with 409 and nothing is stored`() {
        assertThrows<ConflictException> { runBlocking { handler.handle(command(sha = "0".repeat(64))) } }

        coVerify(exactly = 0) { documents.storeEmployeeSigned(any(), any(), any(), any()) }
        verify(exactly = 0) { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `file changed in storage after the session was issued is rejected`() {
        every { documents.download(request.documentS3Key) } returns "tampered".toByteArray()

        assertThrows<ConflictException> { runBlocking { handler.handle(command()) } }
        coVerify(exactly = 0) { documents.storeEmployeeSigned(any(), any(), any(), any()) }
    }

    @Test
    fun `consumed or unknown challenge is rejected before the document is even read`() {
        assertThrows<ConflictException> { runBlocking { handler.handle(command(challenge = "replayed")) } }

        verify(exactly = 0) { documents.download(any()) }
        verify(exactly = 0) { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `losing the race deletes only its own signed file`() {
        every { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) } returns 0

        assertThrows<ConflictException> { runBlocking { handler.handle(command()) } }

        coVerify(exactly = 1) { documents.deleteQuietly(storedKey) }
        assertEquals(1, tx.rollbacks)
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `declaration must be accepted`() {
        val e = assertThrows<ValidationException> { runBlocking { handler.handle(command(declaration = false)) } }
        assertEquals("declarationAccepted", e.field)
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `term taken in the meantime by another pending request blocks the submission`() {
        every { leaveRequestRepository.findOverlappingOfEmployee(any(), any(), any(), any(), any(), request.id) } returns listOf(
            LeaveRequestFixtures.request(studio, employee.id, userId.value, status = LeaveRequestStatus.PENDING)
        )

        assertThrows<ValidationException> { runBlocking { handler.handle(command()) } }
        coVerify(exactly = 1) { documents.deleteQuietly(storedKey) }
        verify(exactly = 0) { leaveRequestRepository.markSubmitted(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `someone else's draft is not found`() {
        val foreign = LeaveRequestFixtures.request(studio, UUID.randomUUID(), UUID.randomUUID(), status = LeaveRequestStatus.DRAFT)
        every { leaveRequestRepository.findByIdAndStudioId(foreign.id, studio.value) } returns foreign

        assertThrows<NotFoundException> { runBlocking { handler.handle(command().copy(requestId = foreign.id)) } }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `already submitted request cannot be submitted again`() {
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns
            LeaveRequestFixtures.request(studio, employee.id, userId.value, status = LeaveRequestStatus.PENDING, id = request.id)

        assertThrows<ConflictException> { runBlocking { handler.handle(command()) } }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }
}
