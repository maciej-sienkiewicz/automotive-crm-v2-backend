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
 * Enforces [RequiresPermission] declarations on controller methods or classes.
 *
 * Method-level annotation takes precedence over class-level. When only the class
 * is annotated, every public method on that class inherits the same permission check.
 *
 * The check fires after [pl.detailing.crm.config.SubscriptionInterceptor] (which validates
 * billing status) and after [pl.detailing.crm.subscription.entitlement.FeatureAuthorizationAspect]
 * (which guards module-level access). This aspect handles fine-grained action-level control.
 *
 * Studio owners are always granted access without a DB lookup.
 */
@Aspect
@Component
class PermissionAuthorizationAspect(
    private val permissionCheckService: PermissionCheckService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(pl.detailing.crm.role.permission.RequiresPermission) || @within(pl.detailing.crm.role.permission.RequiresPermission)")
    fun enforcePermissionAccess(joinPoint: ProceedingJoinPoint): Any? {
        val signature = joinPoint.signature as? MethodSignature
        val method = signature?.method

        // Method-level annotation takes precedence over class-level.
        val requiresPermission = method?.getAnnotation(RequiresPermission::class.java)
            ?: joinPoint.target.javaClass.getAnnotation(RequiresPermission::class.java)
            ?: return joinPoint.proceed()

        val principal = try {
            SecurityContextHelper.getCurrentUser()
        } catch (e: Exception) {
            throw UnauthorizedException("Wymagane uwierzytelnienie")
        }

        val permission = requiresPermission.value

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
