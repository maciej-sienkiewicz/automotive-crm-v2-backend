package pl.detailing.crm.subscription.entitlement.capability

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.UnauthorizedException

/**
 * Enforces [RequiresCapability] declarations on controller methods and classes.
 *
 * Runs at [Order] 0 — BEFORE [pl.detailing.crm.role.permission.PermissionAuthorizationAspect]
 * (which uses the default, lowest precedence). The request pipeline is therefore:
 *
 * 1. SubscriptionInterceptor — billing status (is the studio accessible at all?)
 * 2. This aspect              — capability / entitlement (did the studio buy the module?) → 402
 * 3. Permission aspect        — RBAC (may this user perform the action?)               → 403
 *
 * Owners are NOT exempt here: capability checks bind the studio, not the user.
 *
 * The studio identity comes from the server-side session ([SecurityContextHelper]),
 * never from a client-supplied header or claim.
 */
@Aspect
@Component
@Order(0)
class CapabilityAuthorizationAspect(
    private val capabilityService: CapabilityService
) {

    @Around(
        "@within(pl.detailing.crm.subscription.entitlement.capability.RequiresCapability) || " +
        "@annotation(pl.detailing.crm.subscription.entitlement.capability.RequiresCapability)"
    )
    fun enforceCapabilityAccess(joinPoint: ProceedingJoinPoint): Any? {
        val studioId = try {
            SecurityContextHelper.getCurrentStudioId()
        } catch (e: Exception) {
            throw UnauthorizedException("Wymagane uwierzytelnienie")
        }

        val method = (joinPoint.signature as? MethodSignature)?.method
        val annotation = method?.getAnnotation(RequiresCapability::class.java)
            ?: joinPoint.target.javaClass.getAnnotation(RequiresCapability::class.java)
            ?: return joinPoint.proceed()

        capabilityService.requireCapability(studioId, annotation.value)
        return joinPoint.proceed()
    }
}
