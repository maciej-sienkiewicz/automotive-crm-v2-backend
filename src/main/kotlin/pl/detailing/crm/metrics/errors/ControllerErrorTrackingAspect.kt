package pl.detailing.crm.metrics.errors

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.AfterThrowing
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.HandlerMapping
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.metrics.domain.ErrorOrigin
import pl.detailing.crm.metrics.domain.ErrorSeverity
import pl.detailing.crm.shared.*

/**
 * Captures backend exceptions with their tenant, at the moment they escape a controller.
 *
 * ## Why an aspect rather than editing `GlobalExceptionHandler`
 *
 * The advice already owns HTTP response mapping and is the busiest cross-cutting class in
 * the project. Threading a recorder through each of its ~20 handlers would mean touching
 * every one of them again for every future change to error tracking. `@AfterThrowing` runs
 * before the advice, sees the same exception, and leaves the response mapping untouched —
 * error *tracking* and error *presentation* stay independent.
 *
 * ## What is deliberately not recorded
 *
 * Expected business exceptions — validation failures, 404s, permission denials — are
 * normal application behaviour, not defects. Recording them would bury the nine real
 * defects under fifty thousand "user typed a bad NIP" rows and train everyone to ignore
 * the console. Two exceptions to that rule, both signals rather than noise:
 * [InsufficientSmsCreditsException] (a customer is blocked from working and someone
 * should call them) and cross-tenant access denials (a security signal), which are kept
 * at [ErrorSeverity.WARNING].
 */
@Aspect
@Component
class ControllerErrorTrackingAspect(
    private val errorTrackingService: ErrorTrackingService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    fun restControllerClass() {}

    @Pointcut("execution(public * *(..))")
    fun publicMethod() {}

    @AfterThrowing(pointcut = "restControllerClass() && publicMethod()", throwing = "ex")
    fun recordControllerException(joinPoint: JoinPoint, ex: Throwable) {
        try {
            val severity = severityOf(ex) ?: return

            val principal = SecurityContextHolder.getContext().authentication as? UserPrincipal
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

            errorTrackingService.recordBackendError(
                throwable = ex,
                studioId = principal?.studioId,
                userId = principal?.userId,
                origin = ErrorOrigin.BACKEND,
                severity = severity,
                httpMethod = request?.method,
                path = request?.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString()
                    ?: request?.requestURI,
                httpStatus = statusOf(ex),
                context = mapOf(
                    "controller" to joinPoint.signature.declaringType.simpleName,
                    "handler" to joinPoint.signature.name
                )
            )
        } catch (t: Exception) {
            log.debug("Śledzenie błędu kontrolera nie powiodło się: {}", t.message)
        }
    }

    /** @return null for exceptions that are normal application behaviour and must not be tracked. */
    private fun severityOf(ex: Throwable): ErrorSeverity? = when (ex) {
        is ValidationException,
        is EntityNotFoundException,
        is NotFoundException,
        is ConflictException,
        is UnprocessableEntityException,
        is UnauthorizedException,
        is VehiclePlateExistsException,
        is AlreadyLinkedException,
        is FeatureLockedException,
        is CapabilityLockedException -> null

        // Business-expected, but the customer is blocked from working — worth a signal.
        is InsufficientSmsCreditsException -> ErrorSeverity.WARNING

        // Denials are rare in normal use and can indicate a broken UI or a probe.
        is ForbiddenException -> ErrorSeverity.WARNING

        // Everything unrecognised is, by definition, unhandled.
        else -> ErrorSeverity.ERROR
    }

    /** Mirrors `GlobalExceptionHandler`'s mapping for the recorded status column. */
    private fun statusOf(ex: Throwable): Int = when (ex) {
        is UnauthorizedException -> 401
        is ForbiddenException, is FeatureLockedException, is CapabilityLockedException -> 403
        is EntityNotFoundException, is NotFoundException -> 404
        is ConflictException, is VehiclePlateExistsException, is AlreadyLinkedException -> 409
        is ValidationException -> 400
        is UnprocessableEntityException, is InsufficientSmsCreditsException -> 422
        else -> 500
    }
}
