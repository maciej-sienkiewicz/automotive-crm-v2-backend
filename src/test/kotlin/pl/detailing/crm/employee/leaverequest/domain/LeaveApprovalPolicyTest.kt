package pl.detailing.crm.employee.leaverequest.domain

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures.principal
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/** Projekt §2.6: kto rozpatruje wniosek, i że nikt nie rozpatruje własnego. */
class LeaveApprovalPolicyTest {

    private val permissions = mockk<PermissionCheckService>()
    private val policy = LeaveApprovalPolicy(permissions)
    private val studio = StudioId.random()
    private val employeeUserId = UUID.randomUUID()

    @Test
    fun `owner decides on someone else's request`() {
        val owner = principal(studio, owner = true)
        assertEquals(ApprovalBasis.OWNER, policy.basisFor(owner, employeeUserId))
        // Właściciel omija katalog uprawnień — nie ma czego sprawdzać.
        verify(exactly = 0) { permissions.hasPermission(any(), any(), any()) }
    }

    @Test
    fun `holder of the permission decides on the basis of the permission`() {
        val manager = principal(studio)
        every { permissions.hasPermission(manager.userId, studio, Permission.EMPLOYEES_LEAVES_APPROVE) } returns true
        assertEquals(ApprovalBasis.PERMISSION, policy.basisFor(manager, employeeUserId))
    }

    @Test
    fun `user without the permission is refused`() {
        val worker = principal(studio)
        every { permissions.hasPermission(worker.userId, studio, Permission.EMPLOYEES_LEAVES_APPROVE) } returns false
        val e = assertThrows<ForbiddenException> { policy.basisFor(worker, employeeUserId) }
        assertEquals(LeaveApprovalPolicy.NO_PERMISSION, e.message)
    }

    @Test
    fun `employee with the permission cannot decide on their own request`() {
        val manager = principal(studio, userId = UserId(employeeUserId))
        every { permissions.hasPermission(any(), any(), any()) } returns true
        val e = assertThrows<ForbiddenException> { policy.basisFor(manager, employeeUserId) }
        assertEquals(LeaveApprovalPolicy.SELF_DECISION, e.message)
    }

    @Test
    fun `owner with an employee record cannot decide on their own request either`() {
        val owner = principal(studio, userId = UserId(employeeUserId), owner = true)
        val e = assertThrows<ForbiddenException> { policy.basisFor(owner, employeeUserId) }
        assertEquals(LeaveApprovalPolicy.SELF_DECISION, e.message)
    }

    @Test
    fun `permission revoked between opening the drawer and clicking is checked afresh`() {
        val manager = principal(studio)
        every { permissions.hasPermission(manager.userId, studio, Permission.EMPLOYEES_LEAVES_APPROVE) } returnsMany listOf(true, false)

        assertNull(policy.blockedReason(manager, employeeUserId)) // szuflada otwarta: canDecide = true
        assertThrows<ForbiddenException> { policy.basisFor(manager, employeeUserId) } // kliknięcie po odebraniu roli
        verify(exactly = 2) { permissions.hasPermission(manager.userId, studio, Permission.EMPLOYEES_LEAVES_APPROVE) }
    }

    @Test
    fun `blocked reason explains the refusal for the drawer`() {
        val self = principal(studio, userId = UserId(employeeUserId), owner = true)
        assertEquals(LeaveApprovalPolicy.SELF_DECISION, policy.blockedReason(self, employeeUserId))
    }
}
