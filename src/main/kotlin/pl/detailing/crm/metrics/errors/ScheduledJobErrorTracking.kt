package pl.detailing.crm.metrics.errors

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.metrics.domain.ErrorOrigin
import pl.detailing.crm.metrics.domain.ErrorSeverity

/**
 * Captures failures of `@Scheduled` jobs.
 *
 * Background jobs are the blind spot of every error-tracking setup built around HTTP:
 * no user is watching, no response is returned, and Spring's default behaviour is to log
 * the stack trace and move on. This project runs more than a dozen of them — KSeF sync,
 * SMS reminders, IMAP polling, subscription lifecycle — and a silently failing one is
 * exactly the kind of outage that surfaces as a customer complaint two weeks later.
 *
 * Attribution note: a scheduler serves all tenants, so occurrences are recorded with a
 * null `studio_id`. Per-tenant attribution inside a job is possible where the job knows
 * its tenant — those call sites use [ErrorTrackingService.recordBackendError] directly
 * with the studio id in hand.
 */
@Aspect
@Component
class ScheduledJobErrorTracking(
    private val errorTrackingService: ErrorTrackingService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    fun trackScheduledFailure(pjp: ProceedingJoinPoint): Any? {
        return try {
            pjp.proceed()
        } catch (ex: Throwable) {
            try {
                val job = "${pjp.signature.declaringType.simpleName}.${pjp.signature.name}"

                errorTrackingService.recordBackendError(
                    throwable = ex,
                    studioId = null,
                    origin = ErrorOrigin.SCHEDULED_JOB,
                    // A failing scheduler has no user-visible retry and no one watching it,
                    // so it is escalated above an ordinary request failure by default.
                    severity = ErrorSeverity.CRITICAL,
                    context = mapOf("job" to job)
                )
            } catch (t: Exception) {
                log.debug("Śledzenie błędu zadania cyklicznego nie powiodło się: {}", t.message)
            }
            throw ex
        }
    }
}
