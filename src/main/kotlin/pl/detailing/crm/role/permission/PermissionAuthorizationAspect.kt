package pl.detailing.crm.role.permission

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UnauthorizedException

/**
 * Enforces [RequiresPermission] and [RequiresOwner] declarations on controller methods
 * and classes.
 *
 * Resolution rules:
 * - a class-level [RequiresOwner] applies to every method and cannot be weakened by a
 *   method-level [RequiresPermission] (owner-only always wins),
 * - otherwise a method-level annotation takes precedence over the class-level one,
 * - [RequiresPermission] with multiple values grants access when the user has ANY of them,
 * - an empty [RequiresPermission] value list is a programming error → always deny.
 *
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
        "@annotation(pl.detailing.crm.role.permission.RequiresPermission) || " +
        "@within(pl.detailing.crm.role.permission.RequiresOwner) || " +
        "@annotation(pl.detailing.crm.role.permission.RequiresOwner)"
    )
    fun enforcePermissionAccess(joinPoint: ProceedingJoinPoint): Any? {
        val principal = try {
            SecurityContextHelper.getCurrentUser()
        } catch (e: Exception) {
            throw UnauthorizedException("Wymagane uwierzytelnienie")
        }

        val method = (joinPoint.signature as? MethodSignature)?.method
        val targetClass = joinPoint.target.javaClass

        val ownerAnnotation = method?.getAnnotation(RequiresOwner::class.java)
            ?: targetClass.getAnnotation(RequiresOwner::class.java)

        if (ownerAnnotation != null) {
            if (!principal.isOwner) {
                logDenial(principal, "OWNER_ONLY", joinPoint)
                throw ForbiddenException("Ta operacja jest dostępna wyłącznie dla właściciela firmy")
            }
            return joinPoint.proceed()
        }

        // Method-level annotation takes precedence over class-level
        val annotation = method?.getAnnotation(RequiresPermission::class.java)
            ?: targetClass.getAnnotation(RequiresPermission::class.java)
            ?: return joinPoint.proceed()

        val required = annotation.value

        // Fail closed on a misconfigured (empty) annotation instead of silently allowing.
        if (required.isEmpty()) {
            logger.error(
                "Misconfigured @RequiresPermission with no values on {} — denying access",
                joinPoint.signature.toShortString()
            )
            throw ForbiddenException("Brak uprawnień")
        }

        val granted = required.any { permission ->
            permissionCheckService.hasPermission(principal.userId, principal.studioId, permission)
        }

        if (!granted) {
            logDenial(principal, required.joinToString(",") { it.name }, joinPoint)
            throw ForbiddenException(
                "Brak uprawnienia: ${required.joinToString(" lub ") { it.displayName }}"
            )
        }

        return joinPoint.proceed()
    }

    private fun logDenial(principal: UserPrincipal, requirement: String, joinPoint: ProceedingJoinPoint) {
        logger.debug(
            "Permission denied: userId={} studioId={} required={} method={}",
            principal.userId, principal.studioId, requirement, joinPoint.signature.toShortString()
        )
    }
}
