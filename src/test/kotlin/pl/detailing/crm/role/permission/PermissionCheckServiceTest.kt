package pl.detailing.crm.role.permission

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

class PermissionCheckServiceTest {

    private val snapshotCache = mockk<PermissionSnapshotCache>()
    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()
    private val entitlementService = mockk<EntitlementService>()

    private val service = PermissionCheckService(
        snapshotCache, userRepository, roleRepository, entitlementService
    )

    private val userId = UserId(UUID.randomUUID())
    private val studioId = StudioId(UUID.randomUUID())

    @Test
    fun `owner has every permission and getPermissions returns null`() {
        every { snapshotCache.snapshot(userId.value, studioId.value) } returns
            PermissionsSnapshot(owner = true, permissionCodes = emptyList())

        assertNull(service.getPermissions(userId, studioId))
        assertTrue(service.hasPermission(userId, studioId, Permission.EMPLOYEES_PAYROLL))
    }

    @Test
    fun `user without a role has no permissions`() {
        every { snapshotCache.snapshot(userId.value, studioId.value) } returns
            PermissionsSnapshot(owner = false, permissionCodes = emptyList())

        assertEquals(emptySet<Permission>(), service.getPermissions(userId, studioId))
        assertFalse(service.hasPermission(userId, studioId, Permission.VISITS_VIEW))
    }

    @Test
    fun `granted permission passes when its feature is enabled`() {
        every { snapshotCache.snapshot(userId.value, studioId.value) } returns
            PermissionsSnapshot(owner = false, permissionCodes = listOf(Permission.VISITS_VIEW.name))
        every { entitlementService.hasFeature(studioId, FeatureKey.VISITS) } returns true

        assertTrue(service.hasPermission(userId, studioId, Permission.VISITS_VIEW))
        assertFalse(service.hasPermission(userId, studioId, Permission.VISITS_CREATE))
    }

    @Test
    fun `permission is filtered out when the studio lacks the feature`() {
        every { snapshotCache.snapshot(userId.value, studioId.value) } returns
            PermissionsSnapshot(owner = false, permissionCodes = listOf(Permission.VISITS_VIEW.name))
        every { entitlementService.hasFeature(studioId, FeatureKey.VISITS) } returns false

        assertEquals(emptySet<Permission>(), service.getPermissions(userId, studioId))
    }

    @Test
    fun `legacy codes stored in an old snapshot are mapped, unknown codes are dropped`() {
        every { snapshotCache.snapshot(userId.value, studioId.value) } returns
            PermissionsSnapshot(
                owner = false,
                permissionCodes = listOf("CALENDAR_VIEW", "TOTALLY_UNKNOWN", Permission.TASKS_VIEW.name)
            )
        every { entitlementService.hasFeature(studioId, FeatureKey.VISITS) } returns true

        val permissions = service.getPermissions(userId, studioId)

        assertEquals(setOf(Permission.VISITS_VIEW, Permission.TASKS_VIEW), permissions)
    }
}
