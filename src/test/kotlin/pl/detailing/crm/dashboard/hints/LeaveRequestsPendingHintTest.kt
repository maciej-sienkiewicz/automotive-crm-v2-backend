package pl.detailing.crm.dashboard.hints

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/** „N wniosków urlopowych czeka" — dla tych, którzy mogą je rozpatrzyć, z poprawną odmianą. */
class LeaveRequestsPendingHintTest {

    private val leaveRequests = mockk<LeaveRequestRepository>()
    private val permissions = mockk<PermissionCheckService>()
    private val handler = GetDashboardHintsHandler(
        userRepository = mockk(relaxed = true),
        roleRepository = mockk(relaxed = true),
        workTimePeriodRepository = mockk(relaxed = true),
        instagramReportRepository = mockk(relaxed = true),
        commThreadRepository = mockk(relaxed = true),
        ksefCredentialsRepository = mockk(relaxed = true),
        awaitingWorkService = mockk(relaxed = true),
        areaDiscovery = mockk(relaxed = true),
        visitRepository = mockk(relaxed = true),
        dismissalRepository = mockk(relaxed = true),
        permissionCheckService = permissions,
        objectMapper = mockk(relaxed = true),
        leaveRequestRepository = leaveRequests
    )
    private val studio = StudioId.random()

    @Test
    fun `polish plural of the hint`() {
        assertEquals("1 wniosek urlopowy czeka.", GetDashboardHintsHandler.leaveRequestsPendingText(1))
        assertEquals("3 wnioski urlopowe czekają.", GetDashboardHintsHandler.leaveRequestsPendingText(3))
        assertEquals("5 wniosków urlopowych czeka.", GetDashboardHintsHandler.leaveRequestsPendingText(5))
        assertEquals("12 wniosków urlopowych czeka.", GetDashboardHintsHandler.leaveRequestsPendingText(12))
        assertEquals("22 wnioski urlopowe czekają.", GetDashboardHintsHandler.leaveRequestsPendingText(22))
    }

    @Test
    fun `approver sees the pending requests hint linking to the queue`() {
        val manager = LeaveRequestFixtures.principal(studio)
        every { permissions.hasPermission(any(), any(), any()) } returns false
        every { permissions.hasPermission(manager.userId, studio, pl.detailing.crm.role.domain.Permission.EMPLOYEES_LEAVES_APPROVE) } returns true
        every { leaveRequests.countPendingDecidableBy(studio.value, manager.userId.value) } returns 2
        every { leaveRequests.latestPendingSubmissionFor(studio.value, manager.userId.value) } returns Instant.ofEpochSecond(1_790_000_000)

        val hint = runBlocking { handler.handle(manager) }.single { it.kind == DashboardHintKind.LEAVE_REQUESTS_PENDING }

        assertEquals("2 wnioski urlopowe czekają.", hint.text)
        assertEquals("/employees/leave-requests", hint.action?.url)
        assertEquals("LEAVE_REQUESTS_PENDING_1790000000", hint.key)
    }

    @Test
    fun `user who cannot approve gets no hint`() {
        val worker = LeaveRequestFixtures.principal(studio)
        every { permissions.hasPermission(any(), any(), any()) } returns false

        val hints = runBlocking { handler.handle(worker) }

        assertTrue(hints.none { it.kind == DashboardHintKind.LEAVE_REQUESTS_PENDING })
    }
}
