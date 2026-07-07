package pl.detailing.crm.role.permission

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UnauthorizedException

/**
 * Enforces [RequiresPermission] declarations on controller methods and classes.
 *
 * Method-level annotation takes precedence over class-level annotation.
 * Studio owners are always granted access without a DB lookup.
 */
@Aspect
@Component
class PermissionAuthorizationAspect(
    private val permissionCheckService: PermissionCheckService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Around(
        "@within(pl.detailing.crm.role.permission.RequiresPermission) || " +
        "@annotation(pl.detailing.crm.role.permission.RequiresPermission)"
    )
    fun enforcePermissionAccess(joinPoint: ProceedingJoinPoint): Any? {
        val principal = try {
            SecurityContextHelper.getCurrentUser()
        } catch (e: Exception) {
            throw UnauthorizedException("Wymagane uwierzytelnienie")
        }

        // Method-level annotation takes precedence over class-level
        val methodAnnotation = (joinPoint.signature as? MethodSignature)
            ?.method
            ?.getAnnotation(RequiresPermission::class.java)

        val classAnnotation = joinPoint.target.javaClass
            .getAnnotation(RequiresPermission::class.java)

        val annotation = methodAnnotation ?: classAnnotation ?: return joinPoint.proceed()

        val permission = annotation.value

        if (!permissionCheckService.hasPermission(principal.userId, principal.studioId, permission)) {
            logger.debug(
                "Permission denied: userId={} studioId={} permission={} method={}",
                principal.userId, principal.studioId, permission, joinPoint.signature.toShortString()
            )
            throw ForbiddenException("Brak uprawnienia: ${permission.displayName}")
        }

        return joinPoint.proceed()
    }
}
