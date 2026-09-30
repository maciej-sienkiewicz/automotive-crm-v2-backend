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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.domain.ApprovalBasis
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestDecidedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.user.signature.UserSignatureService
import java.util.UUID

/**
 * Decyzja pracodawcy: podpisana, raz, przez uprawnioną osobę, która nie jest wnioskodawcą.
 * Zatwierdzenie tworzy dokładnie jeden wpis urlopu; druga decyzja dostaje 409.
 */
class DecideLeaveRequestHandlerTest {

    private val studio = StudioId.random()
    private val employeeUser = UserId.random()
    private val employee = LeaveRequestFixtures.employee(studio, employeeUser)

    private val integrity = spyk(DocumentIntegrityService(mockk(relaxed = true), mockk(relaxed = true), 15))
    private val h2Bytes = "employee-signed-pdf".toByteArray()
    private val h2Sha = integrity.sha256Hex(h2Bytes)
    private val request = LeaveRequestFixtures.request(
        studio, employee.id, employeeUser.value, status = LeaveRequestStatus.PENDING,
        employeeSignedSha256 = h2Sha, onDemand = true, leaveType = LeaveType.ANNUAL
    )

    private val permissions = mockk<PermissionCheckService>()
    private val leaveRequestRepository = mockk<LeaveRequestRepository>()
    private val employeeRepository = mockk<EmployeeRepository>()
    private val employeeLeaveRepository = mockk<EmployeeLeaveRepository>()
    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()
    private val userSignatureService = mockk<UserSignatureService>()
    private val documents = mockk<LeaveRequestDocumentService>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val tx = RecordingTransactionManager()

    private val handler = DecideLeaveRequestHandler(
        access = LeaveRequestAccess(employeeRepository, leaveRequestRepository),
        policy = LeaveApprovalPolicy(permissions),
        leaveRequestRepository = leaveRequestRepository,
        employeeRepository = employeeRepository,
        employeeLeaveRepository = employeeLeaveRepository,
        userRepository = userRepository,
        roleRepository = roleRepository,
        userSignatureService = userSignatureService,
        sessions = LeaveSigningSessions(integrity),
        documents = documents,
        signatureImageProcessor = SignatureImageProcessor(),
        auditService = mockk<AuditService>(relaxed = true),
        eventPublisher = eventPublisher,
        transactionTemplate = tx.template()
    )

    private val owner = LeaveRequestFixtures.principal(studio, owner = true, name = "Właściciel Studia")
    private val finalKey = "${studio.value}/leave-requests/${request.id}/final-abc.pdf"
    private val savedLeave = slot<EmployeeLeaveEntity>()

    init {
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns request
        every { employeeRepository.findByIdAndStudioId(employee.id, studio.value) } returns employee
        every { employeeRepository.lockForUpdate(employee.id, studio.value) } returns employee.id
        every { employeeLeaveRepository.findOverlappingOfEmployee(any(), any(), any(), any()) } returns emptyList()
        every { employeeLeaveRepository.save(capture(savedLeave)) } answers { firstArg() }
        every { leaveRequestRepository.markDecided(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        every { integrity.consumeChallenge(any(), any()) } returns false
        every { integrity.consumeChallenge(any(), "good") } returns true
        every { documents.download(request.employeeSignedPdfS3Key!!) } returns h2Bytes
        coEvery { documents.storeFinal(any(), any(), any(), any(), any()) } returns
            LeaveRequestDocumentService.StoredPdf(finalKey, "f".repeat(64), byteArrayOf(1))
        coEvery { documents.deleteQuietly(any()) } just Runs
    }

    private fun command(
        approve: Boolean = true,
        note: String? = null,
        sha: String = h2Sha,
        useSaved: Boolean = false,
        principal: pl.detailing.crm.auth.UserPrincipal = owner
    ) = DecideLeaveRequestCommand(
        principal = principal,
        requestId = request.id,
        approve = approve,
        signatureImageBase64 = if (useSaved) null else LeaveRequestFixtures.signatureBase64(),
        useSavedSignature = useSaved,
        documentSha256 = sha,
        challenge = "good",
        note = note,
        ipAddress = "10.0.0.9",
        userAgent = "Mozilla/5.0"
    )

    @Test
    fun `approval creates exactly one leave entry linked to the request`() {
        runBlocking { handler.handle(command()) }

        verify(exactly = 1) { employeeLeaveRepository.save(any()) }
        val leave = savedLeave.captured
        assertEquals(request.id, leave.leaveRequestId)
        assertEquals(employee.id, leave.employeeId)
        // Urlop na żądanie trafia do kalendarza jako zwykły wypoczynkowy.
        assertEquals(LeaveType.ANNUAL, leave.leaveType)
        assertEquals(request.startDate, leave.startDate)
        verify(exactly = 1) {
            leaveRequestRepository.markDecided(
                request.id, studio.value, LeaveRequestStatus.APPROVED, owner.userId.value, "Właściciel Studia",
                ApprovalBasis.OWNER, null, any(), null, LeaveSignatureMethod.DEVICE_DRAWN, h2Sha, "10.0.0.9", "Mozilla/5.0",
                finalKey, "f".repeat(64), leave.id
            )
        }
        verify(exactly = 1) { eventPublisher.publishEvent(match<Any> { it is LeaveRequestDecidedEvent && it.outcome == LeaveRequestStatus.APPROVED }) }
        assertEquals(1, tx.commits)
    }

    @Test
    fun `rejection requires a note`() {
        val e = assertThrows<ValidationException> { runBlocking { handler.handle(command(approve = false)) } }
        assertEquals("note", e.field)
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `rejection is signed too and creates no leave`() {
        runBlocking { handler.handle(command(approve = false, note = "Brak obsady")) }

        verify(exactly = 0) { employeeLeaveRepository.save(any()) }
        verify(exactly = 1) {
            leaveRequestRepository.markDecided(
                request.id, studio.value, LeaveRequestStatus.REJECTED, any(), any(), any(), any(), any(), "Brak obsady",
                any(), any(), any(), any(), finalKey, any(), null
            )
        }
        coVerify(exactly = 1) { documents.storeFinal(any(), any(), h2Bytes, any(), match { !it.approved }) }
    }

    @Test
    fun `second decision loses with 409, its leave entry is rolled back and its file deleted`() {
        every { leaveRequestRepository.markDecided(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 0

        assertThrows<ConflictException> { runBlocking { handler.handle(command()) } }

        assertEquals(1, tx.rollbacks)
        assertEquals(0, tx.commits)
        coVerify(exactly = 1) { documents.deleteQuietly(finalKey) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<Any>()) }
    }

    @Test
    fun `already decided request answers 409 before the challenge is touched`() {
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns
            LeaveRequestFixtures.request(studio, employee.id, employeeUser.value, status = LeaveRequestStatus.APPROVED, id = request.id)

        assertThrows<ConflictException> { runBlocking { handler.handle(command()) } }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
    }

    @Test
    fun `nobody decides on their own request`() {
        val self = LeaveRequestFixtures.principal(studio, userId = employeeUser, owner = true)

        assertThrows<ForbiddenException> { runBlocking { handler.handle(command(principal = self)) } }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
        verify(exactly = 0) { leaveRequestRepository.markDecided(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `hash of a document other than the employee-signed version is rejected`() {
        assertThrows<ConflictException> { runBlocking { handler.handle(command(sha = request.documentSha256)) } }
        coVerify(exactly = 0) { documents.storeFinal(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `saved signature is required when asked for`() {
        every { userSignatureService.downloadBytes(studio, owner.userId) } returns null

        val e = assertThrows<ValidationException> { runBlocking { handler.handle(command(useSaved = true)) } }
        assertEquals("Nie masz zapisanego podpisu", e.message)
        assertEquals("useSavedSignature", e.field)
    }

    @Test
    fun `decision on the basis of the permission snapshots the role name and the saved signature method`() {
        val manager = LeaveRequestFixtures.principal(studio, name = "Anna Nowak")
        val roleId = UUID.randomUUID()
        every { permissions.hasPermission(manager.userId, studio, Permission.EMPLOYEES_LEAVES_APPROVE) } returns true
        every { userSignatureService.downloadBytes(studio, manager.userId) } returns LeaveRequestFixtures.signaturePng()
        every { userRepository.findByIdAndStudioId(manager.userId.value, studio.value) } returns mockk<UserEntity> {
            every { customRoleId } returns roleId
        }
        every { roleRepository.findByIdAndStudioId(roleId, studio.value) } returns mockk<RoleEntity> {
            every { name } returns "Kierownik zmiany"
        }

        runBlocking { handler.handle(command(useSaved = true, principal = manager)) }

        verify(exactly = 1) {
            leaveRequestRepository.markDecided(
                request.id, studio.value, LeaveRequestStatus.APPROVED, manager.userId.value, "Anna Nowak",
                ApprovalBasis.PERMISSION, "Kierownik zmiany", any(), null, LeaveSignatureMethod.SAVED_SIGNATURE,
                any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `leave entered in the meantime (for example sick leave) blocks the approval`() {
        every { employeeLeaveRepository.findOverlappingOfEmployee(studio.value, employee.id, any(), any()) } returns listOf(
            EmployeeLeaveEntity(
                id = UUID.randomUUID(), studioId = studio.value, employeeId = employee.id, leaveType = LeaveType.SICK,
                startDate = request.startDate, endDate = request.endDate, note = null, createdBy = UUID.randomUUID()
            )
        )

        assertThrows<ConflictException> { runBlocking { handler.handle(command()) } }
        verify(exactly = 0) { employeeLeaveRepository.save(any()) }
        coVerify(exactly = 1) { documents.deleteQuietly(finalKey) }
    }
}
