package pl.detailing.crm.role.permission

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.UserId
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals

class PermissionAuthorizationAspectTest {

    private val permissionCheckService = mockk<PermissionCheckService>()
    private val aspect = PermissionAuthorizationAspect(permissionCheckService)

    private val userId = UserId(UUID.randomUUID())
    private val studioId = StudioId(UUID.randomUUID())

    // ── Fixture controllers ──────────────────────────────────────────────────

    @RequiresPermission(Permission.VISITS_VIEW)
    open class ClassGatedController {
        fun inherited() = "ok"

        @RequiresPermission(Permission.VISITS_DELETE)
        fun overridden() = "ok"
    }

    open class MethodGatedController {
        @RequiresPermission(Permission.FINANCE_VIEW_REPORTS, Permission.STATISTICS_VIEW)
        fun anyOf() = "ok"

        @RequiresPermission
        fun misconfigured() = "ok"
    }

    @RequiresOwner
    open class OwnerGatedController {
        fun anything() = "ok"

        @RequiresPermission(Permission.VISITS_VIEW)
        fun cannotWeaken() = "ok"
    }

    @BeforeEach
    fun setUpPrincipal() = setPrincipal(isOwner = false)

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    private fun setPrincipal(isOwner: Boolean) {
        val principal = UserPrincipal(
            userId = userId, studioId = studioId, isOwner = isOwner,
            email = "user@example.com", fullName = "Test User", phoneNumber = "500100200"
        )
        SecurityContextHolder.setContext(SecurityContextImpl(principal))
    }

    private fun joinPoint(target: Any, methodName: String): ProceedingJoinPoint {
        val method = target.javaClass.getDeclaredMethod(methodName)
        val signature = mockk<MethodSignature>(relaxed = true) {
            every { getMethod() } returns method
        }
        return mockk(relaxed = true) {
            every { getSignature() } returns signature
            every { getTarget() } returns target
            every { proceed() } returns "proceeded"
        }
    }

    // ── @RequiresPermission ──────────────────────────────────────────────────

    @Test
    fun `class-level annotation gates every method`() {
        every { permissionCheckService.hasPermission(userId, studioId, Permission.VISITS_VIEW) } returns false

        assertThrows<ForbiddenException> {
            aspect.enforcePermissionAccess(joinPoint(ClassGatedController(), "inherited"))
        }
    }

    @Test
    fun `method-level annotation takes precedence over class-level`() {
        every { permissionCheckService.hasPermission(userId, studioId, Permission.VISITS_DELETE) } returns true

        assertEquals("proceeded", aspect.enforcePermissionAccess(joinPoint(ClassGatedController(), "overridden")))
        verify(exactly = 0) { permissionCheckService.hasPermission(userId, studioId, Permission.VISITS_VIEW) }
    }

    @Test
    fun `any-of grants access when at least one permission is held`() {
        every { permissionCheckService.hasPermission(userId, studioId, Permission.FINANCE_VIEW_REPORTS) } returns false
        every { permissionCheckService.hasPermission(userId, studioId, Permission.STATISTICS_VIEW) } returns true

        assertEquals("proceeded", aspect.enforcePermissionAccess(joinPoint(MethodGatedController(), "anyOf")))
    }

    @Test
    fun `any-of denies when no permission is held`() {
        every { permissionCheckService.hasPermission(userId, studioId, any()) } returns false

        assertThrows<ForbiddenException> {
            aspect.enforcePermissionAccess(joinPoint(MethodGatedController(), "anyOf"))
        }
    }

    @Test
    fun `empty annotation fails closed`() {
        assertThrows<ForbiddenException> {
            aspect.enforcePermissionAccess(joinPoint(MethodGatedController(), "misconfigured"))
        }
        verify(exactly = 0) { permissionCheckService.hasPermission(any(), any(), any()) }
    }

    @Test
    fun `missing principal is unauthorized, not forbidden`() {
        SecurityContextHolder.clearContext()

        assertThrows<UnauthorizedException> {
            aspect.enforcePermissionAccess(joinPoint(ClassGatedController(), "inherited"))
        }
    }

    // ── @RequiresOwner ───────────────────────────────────────────────────────

    @Test
    fun `owner-only denies a regular user without consulting permissions`() {
        assertThrows<ForbiddenException> {
            aspect.enforcePermissionAccess(joinPoint(OwnerGatedController(), "anything"))
        }
        verify(exactly = 0) { permissionCheckService.hasPermission(any(), any(), any()) }
    }

    @Test
    fun `owner-only admits the owner`() {
        setPrincipal(isOwner = true)

        assertEquals("proceeded", aspect.enforcePermissionAccess(joinPoint(OwnerGatedController(), "anything")))
    }

    @Test
    fun `method-level RequiresPermission cannot weaken class-level RequiresOwner`() {
        every { permissionCheckService.hasPermission(userId, studioId, Permission.VISITS_VIEW) } returns true

        assertThrows<ForbiddenException> {
            aspect.enforcePermissionAccess(joinPoint(OwnerGatedController(), "cannotWeaken"))
        }
    }
}
