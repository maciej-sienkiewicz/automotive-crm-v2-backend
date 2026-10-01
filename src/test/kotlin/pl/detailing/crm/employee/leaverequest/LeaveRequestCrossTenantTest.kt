package pl.detailing.crm.employee.leaverequest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leaverequest.cancel.CancelLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.cancel.CancelLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessionService
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.employee.leaverequest.withdraw.WithdrawLeaveRequestHandler
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService

/**
 * Cross-Tenant Data Access — wnioski urlopowe (wzorzec TeamWorkTimeCrossTenantTest).
 *
 * Każda ścieżka szuka wniosku po (id, studio_id z sesji). Menedżer studia A, znając
 * identyfikator wniosku ze studia B, nie może go ani rozpatrzyć, ani odwołać, ani dostać
 * do niego sesji podpisu — a pracownik studia A nie wycofa cudzego wniosku.
 */
class LeaveRequestCrossTenantTest {

    private val studioA = StudioId.random()
    private val studioB = StudioId.random()
    private val employeeB = LeaveRequestFixtures.employee(studioB, UserId.random())
    private val requestB = LeaveRequestFixtures.request(studioB, employeeB.id, employeeB.userId, status = LeaveRequestStatus.PENDING)

    private val leaveRequestRepository = mockk<LeaveRequestRepository>(relaxed = true)
    private val employeeRepository = mockk<EmployeeRepository>(relaxed = true)
    private val integrity = mockk<DocumentIntegrityService>(relaxed = true)
    private val access = LeaveRequestAccess(employeeRepository, leaveRequestRepository)
    private val policy = LeaveApprovalPolicy(mockk(relaxed = true))
    private val managerA = LeaveRequestFixtures.principal(studioA, owner = true)

    init {
        // Nieograniczone zapytanie po samym id znalazłoby wniosek…
        every { leaveRequestRepository.findById(requestB.id) } returns java.util.Optional.of(requestB)
        // …zapytanie z najemcą A — nie.
        every { leaveRequestRepository.findByIdAndStudioId(requestB.id, studioA.value) } returns null
        every { leaveRequestRepository.findByIdAndStudioId(requestB.id, studioB.value) } returns requestB
    }

    @Test
    fun `manager of studio A cannot decide on a request of studio B`() {
        val handler = DecideLeaveRequestHandler(
            access, policy, leaveRequestRepository, employeeRepository, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), LeaveSigningSessions(integrity), mockk(relaxed = true),
            mockk(relaxed = true), mockk<AuditService>(relaxed = true), mockk(relaxed = true), RecordingTransactionManager().template()
        )
        assertThrows<NotFoundException> {
            runBlocking {
                handler.handle(
                    DecideLeaveRequestCommand(
                        managerA, requestB.id, approve = true, signatureImageBase64 = LeaveRequestFixtures.signatureBase64(),
                        useSavedSignature = false, documentSha256 = requestB.employeeSignedSha256, challenge = "x",
                        note = null, ipAddress = null, userAgent = null
                    )
                )
            }
        }
        verify(exactly = 0) { integrity.consumeChallenge(any(), any()) }
        verify(exactly = 0) { leaveRequestRepository.markDecided(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `manager of studio A gets no decision session for a request of studio B`() {
        val sessions = LeaveSigningSessionService(access, policy, LeaveSigningSessions(integrity))
        assertThrows<NotFoundException> { sessions.forDecision(managerA, requestB.id) }
        verify(exactly = 0) { integrity.issueChallenge(any(), any()) }
    }

    @Test
    fun `manager of studio A cannot cancel an approved leave of studio B`() {
        val handler = CancelLeaveRequestHandler(
            access, policy, leaveRequestRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            RecordingTransactionManager().template()
        )
        assertThrows<NotFoundException> { runBlocking { handler.handle(CancelLeaveRequestCommand(managerA, requestB.id, "pwned")) } }
        verify(exactly = 0) { leaveRequestRepository.markCancelled(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `employee of studio A cannot withdraw a request of studio B`() {
        val userA = UserId.random()
        every { employeeRepository.findByStudioIdAndUserId(studioA.value, userA.value) } returns LeaveRequestFixtures.employee(studioA, userA)
        val handler = WithdrawLeaveRequestHandler(access, leaveRequestRepository, employeeRepository, mockk(relaxed = true), RecordingTransactionManager().template())

        assertThrows<NotFoundException> { runBlocking { handler.handle(studioA, userA, "Ktoś", requestB.id) } }
        verify(exactly = 0) { leaveRequestRepository.markWithdrawn(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `account without an employee record has no self-service`() {
        val userA = UserId.random()
        every { employeeRepository.findByStudioIdAndUserId(studioA.value, userA.value) } returns null
        assertThrows<NotFoundException> { access.employeeOf(studioA, userA) }
    }
}
