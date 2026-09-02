package pl.detailing.crm.role.permission

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.role.assign.AssignRoleHandler
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.update.UpdateRoleCommand
import pl.detailing.crm.role.update.UpdateRoleHandler
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository

/**
 * Privilege Escalation — the exact attack, end to end through the handlers:
 *  1. `PUT /api/v1/roles/{myRole}` with every permission          → refused, role untouched
 *  2. `PUT /api/v1/roles/assign/{me}` with a richer role           → refused, user untouched
 */
class RoleEscalationHandlerTest {

    private val studioId = StudioId.random()
    private val attacker = UserId.random()

    private val roleRepository = mockk<RoleRepository>()
    private val userRepository = mockk<UserRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val snapshotCache = mockk<PermissionSnapshotCache>(relaxed = true)
    private val permissionCheckService = mockk<PermissionCheckService>()
    private val guard = RoleGrantGuard(permissionCheckService)

    private fun attackerHolds(vararg p: Permission) {
        every { permissionCheckService.getPermissions(attacker, studioId) } returns p.toSet()
    }

    private fun role(vararg p: Permission): RoleEntity = RoleEntity(
        id = RoleId.random().value,
        studioId = studioId.value,
        name = "Recepcja",
        description = null,
        permissions = p.map { it.name }.toMutableSet(),
        trackWorkTime = false,
        createdBy = attacker.value
    )

    @Test
    fun `editing own role to include every permission is refused and nothing is persisted`() = runBlocking {
        attackerHolds(Permission.EMPLOYEES_MANAGE)
        val myRole = role(Permission.EMPLOYEES_MANAGE)
        every { roleRepository.findByIdAndStudioId(myRole.id, studioId.value) } returns myRole
        every { roleRepository.existsByStudioIdAndNameExcluding(any(), any(), any()) } returns false
        every { roleRepository.save(any()) } answers { firstArg() }

        val handler = UpdateRoleHandler(roleRepository, auditService, snapshotCache, guard)

        assertThrows<ForbiddenException> {
            handler.handle(
                UpdateRoleCommand(
                    studioId = studioId, requestedBy = attacker, requestedByName = "Attacker",
                    roleId = RoleId(myRole.id), name = "Recepcja", description = null,
                    permissions = Permission.entries.toSet(), trackWorkTime = false
                )
            )
        }

        verify(exactly = 0) { roleRepository.save(any()) }
        verify(exactly = 0) { snapshotCache.evictStudio(any()) }
        assert(myRole.permissions == setOf(Permission.EMPLOYEES_MANAGE.name)) { "role must be untouched" }
    }

    @Test
    fun `assigning a richer role to oneself is refused and the user row is untouched`() = runBlocking {
        attackerHolds(Permission.EMPLOYEES_MANAGE)
        val richRole = role(Permission.EMPLOYEES_MANAGE, Permission.FINANCE_INVOICES, Permission.AUDIT_VIEW)
        val me = UserEntity(
            id = attacker.value, studioId = studioId.value, email = "a@a.pl", phoneNumber = "",
            passwordHash = "x", firstName = "A", lastName = "B", isOwner = false
        )
        every { userRepository.findByIdAndStudioId(attacker.value, studioId.value) } returns me
        every { roleRepository.findByIdAndStudioId(richRole.id, studioId.value) } returns richRole
        every { userRepository.save(any()) } answers { firstArg() }

        val handler = AssignRoleHandler(userRepository, roleRepository, auditService, snapshotCache, guard)

        assertThrows<ForbiddenException> {
            handler.handle(studioId, attacker, RoleId(richRole.id), attacker, "Attacker")
        }

        verify(exactly = 0) { userRepository.save(any()) }
        assert(me.customRoleId == null) { "assignment must not have changed" }
    }
}
