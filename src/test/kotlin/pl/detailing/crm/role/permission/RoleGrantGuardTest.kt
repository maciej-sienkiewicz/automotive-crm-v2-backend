package pl.detailing.crm.role.permission

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * Privilege Escalation — role management.
 *
 * Luka: posiadacz samego `EMPLOYEES_MANAGE` mógł wyedytować własną rolę (albo założyć
 * nową) z kompletem uprawnień i przypisać ją sobie. Te testy dowodzą, że nikt nie nadaje
 * więcej, niż sam ma, i nikt nie zmienia własnej roli — a właściciel pozostaje bez ograniczeń.
 */
class RoleGrantGuardTest {

    private val permissionCheckService = mockk<PermissionCheckService>()
    private val guard = RoleGrantGuard(permissionCheckService)

    private val studioId = StudioId.random()
    private val manager = UserId.random()
    private val colleague = UserId.random()

    private fun managerHolds(vararg permissions: Permission) {
        every { permissionCheckService.getPermissions(manager, studioId) } returns permissions.toSet()
    }

    private fun requesterIsOwner() {
        every { permissionCheckService.getPermissions(manager, studioId) } returns null
    }

    @Test
    fun `EMPLOYEES_MANAGE holder cannot grant finance or audit permissions they do not hold`() {
        managerHolds(Permission.EMPLOYEES_MANAGE)

        val ex = assertThrows<ForbiddenException> {
            guard.assertCanGrant(
                manager, studioId,
                setOf(Permission.EMPLOYEES_MANAGE, Permission.FINANCE_INVOICES, Permission.AUDIT_VIEW)
            )
        }
        assert(ex.message!!.contains(Permission.FINANCE_INVOICES.displayName))
    }

    @Test
    fun `hierarchy closure counts - granting a child implies its parent, which must be held too`() {
        // FINANCE_MANAGE_CASH_REGISTER has parent FINANCE_INVOICES: closing the requested
        // set pulls the parent in, so a manager without FINANCE_INVOICES must be refused.
        managerHolds(Permission.EMPLOYEES_MANAGE, Permission.FINANCE_MANAGE_CASH_REGISTER)

        assertThrows<ForbiddenException> {
            guard.assertCanGrant(manager, studioId, setOf(Permission.FINANCE_MANAGE_CASH_REGISTER))
        }
    }

    @Test
    fun `granting a subset of held permissions is allowed`() {
        managerHolds(Permission.EMPLOYEES_MANAGE, Permission.CUSTOMERS_VIEW, Permission.VISITS_VIEW)

        assertDoesNotThrow {
            guard.assertCanGrant(manager, studioId, setOf(Permission.CUSTOMERS_VIEW))
        }
    }

    @Test
    fun `owner is unrestricted`() {
        requesterIsOwner()

        assertDoesNotThrow {
            guard.assertCanGrant(manager, studioId, Permission.entries.toSet())
            guard.assertCanAssign(manager, studioId, manager, Permission.entries.toSet())
        }
    }

    @Test
    fun `non-owner cannot change their own role assignment`() {
        managerHolds(Permission.EMPLOYEES_MANAGE)

        assertThrows<ForbiddenException> {
            guard.assertCanAssign(manager, studioId, targetUserId = manager, rolePermissions = setOf(Permission.CUSTOMERS_VIEW))
        }
        // …not even to clear it
        assertThrows<ForbiddenException> {
            guard.assertCanAssign(manager, studioId, targetUserId = manager, rolePermissions = null)
        }
    }

    @Test
    fun `non-owner cannot assign a colleague a role richer than their own`() {
        managerHolds(Permission.EMPLOYEES_MANAGE)

        assertThrows<ForbiddenException> {
            guard.assertCanAssign(
                manager, studioId, targetUserId = colleague,
                rolePermissions = setOf(Permission.EMPLOYEES_MANAGE, Permission.EMPLOYEES_PAYROLL)
            )
        }
    }

    @Test
    fun `non-owner may assign a colleague a role within their own permissions`() {
        managerHolds(Permission.EMPLOYEES_MANAGE, Permission.CUSTOMERS_VIEW)

        assertDoesNotThrow {
            guard.assertCanAssign(manager, studioId, targetUserId = colleague, rolePermissions = setOf(Permission.CUSTOMERS_VIEW))
            guard.assertCanAssign(manager, studioId, targetUserId = colleague, rolePermissions = null)
        }
    }
}
