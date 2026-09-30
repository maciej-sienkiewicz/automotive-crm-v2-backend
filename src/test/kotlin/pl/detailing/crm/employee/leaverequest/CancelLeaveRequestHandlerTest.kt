package pl.detailing.crm.employee.leaverequest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.cancel.CancelLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.cancel.CancelLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestDecidedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate
import java.util.UUID

/** Odwołanie zatwierdzonego urlopu zdejmuje z kalendarza dokładnie jego wpis — i tylko przed startem. */
class CancelLeaveRequestHandlerTest {

    private val studio = StudioId.random()
    private val employeeUser = UserId.random()
    private val employeeId = UUID.randomUUID()
    private val request = LeaveRequestFixtures.request(studio, employeeId, employeeUser.value, status = LeaveRequestStatus.APPROVED)

    private val leaveRequestRepository = mockk<LeaveRequestRepository>()
    private val employeeLeaveRepository = mockk<EmployeeLeaveRepository>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val handler = CancelLeaveRequestHandler(
        access = LeaveRequestAccess(mockk(), leaveRequestRepository),
        policy = LeaveApprovalPolicy(mockk()),
        leaveRequestRepository = leaveRequestRepository,
        employeeLeaveRepository = employeeLeaveRepository,
        auditService = mockk<AuditService>(relaxed = true),
        eventPublisher = eventPublisher,
        transactionTemplate = RecordingTransactionManager().template()
    )
    private val owner = LeaveRequestFixtures.principal(studio, owner = true)

    init {
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns request
        every { leaveRequestRepository.markCancelled(any(), any(), any(), any(), any()) } returns 1
        every { employeeLeaveRepository.deleteByLeaveRequestId(any(), any()) } returns 1
    }

    @Test
    fun `cancelling an approved leave removes its calendar entry and tells the employee`() {
        runBlocking { handler.handle(CancelLeaveRequestCommand(owner, request.id, "Zmiana grafiku")) }

        verify(exactly = 1) { leaveRequestRepository.markCancelled(request.id, studio.value, any(), owner.userId.value, "Zmiana grafiku") }
        verify(exactly = 1) { employeeLeaveRepository.deleteByLeaveRequestId(studio.value, request.id) }
        verify(exactly = 1) { eventPublisher.publishEvent(match<Any> { it is LeaveRequestDecidedEvent && it.outcome == LeaveRequestStatus.CANCELLED }) }
    }

    @Test
    fun `reason is required`() {
        val e = assertThrows<ValidationException> { runBlocking { handler.handle(CancelLeaveRequestCommand(owner, request.id, " ")) } }
        assertEquals("reason", e.field)
        verify(exactly = 0) { employeeLeaveRepository.deleteByLeaveRequestId(any(), any()) }
    }

    @Test
    fun `leave that already started cannot be cancelled`() {
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns LeaveRequestFixtures.request(
            studio, employeeId, employeeUser.value, status = LeaveRequestStatus.APPROVED, id = request.id,
            start = LocalDate.now().minusDays(1)
        )
        assertThrows<ValidationException> { runBlocking { handler.handle(CancelLeaveRequestCommand(owner, request.id, "Za późno")) } }
        verify(exactly = 0) { leaveRequestRepository.markCancelled(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `only an approved request can be cancelled`() {
        every { leaveRequestRepository.findByIdAndStudioId(request.id, studio.value) } returns
            LeaveRequestFixtures.request(studio, employeeId, employeeUser.value, status = LeaveRequestStatus.REJECTED, id = request.id)
        assertThrows<ConflictException> { runBlocking { handler.handle(CancelLeaveRequestCommand(owner, request.id, "Powód")) } }
    }

    @Test
    fun `concurrent cancellation leaves the calendar entry alone`() {
        every { leaveRequestRepository.markCancelled(any(), any(), any(), any(), any()) } returns 0
        assertThrows<ConflictException> { runBlocking { handler.handle(CancelLeaveRequestCommand(owner, request.id, "Powód")) } }
        verify(exactly = 0) { employeeLeaveRepository.deleteByLeaveRequestId(any(), any()) }
    }
}
