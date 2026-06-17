package pl.detailing.crm.role.permission

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UnauthorizedException

/**
 * Enforces [RequiresPermission] declarations on controller methods.
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

    @Around("@annotation(requiresPermission)")
    fun enforcePermissionAccess(joinPoint: ProceedingJoinPoint, requiresPermission: RequiresPermission): Any? {
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
