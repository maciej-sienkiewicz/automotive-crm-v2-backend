package pl.detailing.crm.subscription.entitlement

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.FeatureLockedException
import pl.detailing.crm.shared.UnauthorizedException

/**
 * AOP aspect that enforces [RequiresFeature] declarations on controller methods.
 *
 * Execution order:
 * 1. SubscriptionInterceptor validates that the studio's billing status is active
 * 2. This aspect (order=0, default) validates feature-level entitlements
 * 3. The actual controller method executes
 *
 * The check is performed against the Redis-cached [StudioEntitlements] snapshot,
 * so it adds negligible latency (sub-millisecond for a cache hit).
 *
 * Security note: the studio identity comes from the server-side session
 * ([SecurityContextHelper]), never from a client-supplied header or JWT claim.
 */
@Aspect
@Component
class FeatureAuthorizationAspect(
    private val entitlementService: EntitlementService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(requiresFeature)")
    fun enforceFeatureAccess(joinPoint: ProceedingJoinPoint, requiresFeature: RequiresFeature): Any? {
        val studioId = try {
            SecurityContextHelper.getCurrentStudioId()
        } catch (e: Exception) {
            throw UnauthorizedException("Authentication required")
        }

        val featureKey = requiresFeature.value

        if (!entitlementService.hasFeature(studioId, featureKey)) {
            logger.debug(
                "Feature access denied: studio={} feature={} method={}",
                studioId, featureKey, joinPoint.signature.toShortString()
            )
            throw FeatureLockedException(featureKey)
        }

        return joinPoint.proceed()
    }
}
