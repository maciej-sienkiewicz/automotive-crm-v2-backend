package pl.detailing.crm.metrics.errors

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ErrorOrigin
import pl.detailing.crm.metrics.domain.ErrorSeverity
import pl.detailing.crm.metrics.infrastructure.*
import pl.detailing.crm.security.CorrelationIdFilter
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.UUID

/**
 * Records an error occurrence and keeps its group aggregate current.
 *
 * ## Tenant attribution is the whole point
 *
 * A stack trace in a log file tells you what broke. It does not tell you *whose morning
 * you ruined*, and that is the question support gets asked. Every occurrence carries
 * `studio_id`, and the group's impact rows answer "which of our customers hit this, how
 * often, since when" without a log search.
 *
 * `studio_id` is null only where no tenant exists yet — a failed login, a webhook with a
 * bad signature. Everything reaching authenticated code carries it.
 *
 * ## Isolation
 *
 * Writes run in [Propagation.REQUIRES_NEW]. An error is almost always recorded while some
 * business transaction is unwinding; joining that transaction would mean the record is
 * rolled back together with the failure it documents — losing exactly the evidence needed.
 */
@Service
class ErrorTrackingService(
    private val errorEventRepository: ErrorEventRepository,
    private val errorGroupRepository: ErrorGroupRepository,
    private val impactRepository: ErrorGroupImpactRepository,
    private val fingerprinter: ErrorFingerprinter,
    private val properties: MetricsProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Backend / scheduled-job failures.
     *
     * `REQUIRES_NEW` is on the public entry point rather than on an internal helper on
     * purpose: Spring's transaction advice lives on the proxy, so an annotation on a method
     * this class calls itself would be silently ignored and the record would be rolled back
     * with the failing transaction — the classic self-invocation trap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordBackendError(
        throwable: Throwable,
        studioId: StudioId?,
        userId: UserId? = null,
        origin: ErrorOrigin = ErrorOrigin.BACKEND,
        severity: ErrorSeverity = ErrorSeverity.ERROR,
        httpMethod: String? = null,
        path: String? = null,
        httpStatus: Int? = null,
        context: Map<String, Any?>? = null
    ) {
        val stackTrace = stackTraceOf(throwable)
        record(
            origin = origin,
            severity = severity,
            exceptionClass = throwable.javaClass.name,
            message = throwable.message,
            stackTrace = stackTrace,
            studioId = studioId?.value,
            userId = userId?.value,
            httpMethod = httpMethod,
            path = path,
            httpStatus = httpStatus,
            correlationId = currentCorrelationId(),
            appVersion = null,
            userAgent = null,
            context = context
        )
    }

    /** Frontend-reported failures. Same pipeline, same grouping, same tenant attribution. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordFrontendError(
        exceptionClass: String,
        message: String?,
        stackTrace: String?,
        studioId: StudioId,
        userId: UserId?,
        route: String?,
        appVersion: String?,
        userAgent: String?,
        correlationId: UUID?,
        severity: ErrorSeverity,
        context: Map<String, Any?>?
    ) {
        record(
            origin = ErrorOrigin.FRONTEND,
            severity = severity,
            exceptionClass = exceptionClass,
            message = message,
            stackTrace = stackTrace,
            studioId = studioId.value,
            userId = userId?.value,
            httpMethod = null,
            path = route,
            httpStatus = null,
            correlationId = correlationId,
            appVersion = appVersion,
            userAgent = userAgent,
            context = context
        )
    }

    private fun persist(
        event: ErrorEventEntity,
        title: String
    ) {
        errorEventRepository.save(event)

        val group = errorGroupRepository.findById(event.fingerprint).orElse(null)
        if (group == null) {
            errorGroupRepository.save(
                ErrorGroupEntity(
                    fingerprint = event.fingerprint,
                    origin = event.origin,
                    title = title,
                    exceptionClass = event.exceptionClass,
                    severity = event.severity,
                    firstSeenAt = event.occurredAt,
                    lastSeenAt = event.occurredAt,
                    occurrenceCount = 1,
                    affectedStudios = if (event.studioId != null) 1 else 0
                )
            )
        } else {
            group.lastSeenAt = maxOf(group.lastSeenAt, event.occurredAt)
            group.occurrenceCount += 1
            // A defect that comes back after being marked resolved is a regression, and
            // the console must not keep it hidden under a green flag.
            if (group.status == pl.detailing.crm.metrics.domain.ErrorGroupStatus.RESOLVED) {
                group.status = pl.detailing.crm.metrics.domain.ErrorGroupStatus.NEW
                group.resolvedAt = null
            }
            if (event.severity.ordinal > group.severity.ordinal) group.severity = event.severity
            errorGroupRepository.save(group)
        }

        event.studioId?.let { studioId -> upsertImpact(event.fingerprint, studioId, event.occurredAt) }
    }

    private fun upsertImpact(fingerprint: String, studioId: UUID, occurredAt: Instant) {
        val existing = impactRepository.find(fingerprint, studioId)
        if (existing == null) {
            impactRepository.save(
                ErrorGroupImpactEntity(
                    id = UUID.randomUUID(),
                    fingerprint = fingerprint,
                    studioId = studioId,
                    occurrences = 1,
                    firstSeenAt = occurredAt,
                    lastSeenAt = occurredAt,
                    affectedUsers = 1
                )
            )
            errorGroupRepository.findById(fingerprint).ifPresent {
                it.affectedStudios = impactRepository.countStudios(fingerprint)
                errorGroupRepository.save(it)
            }
        } else {
            existing.occurrences += 1
            existing.lastSeenAt = maxOf(existing.lastSeenAt, occurredAt)
            impactRepository.save(existing)
        }
    }

    private fun record(
        origin: ErrorOrigin,
        severity: ErrorSeverity,
        exceptionClass: String,
        message: String?,
        stackTrace: String?,
        studioId: UUID?,
        userId: UUID?,
        httpMethod: String?,
        path: String?,
        httpStatus: Int?,
        correlationId: UUID?,
        appVersion: String?,
        userAgent: String?,
        context: Map<String, Any?>?
    ) {
        if (!properties.enabled) return

        try {
            val fingerprint = fingerprinter.fingerprint(origin, exceptionClass, message, stackTrace)

            val event = ErrorEventEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                userId = userId,
                origin = origin,
                severity = severity,
                fingerprint = fingerprint.value,
                exceptionClass = exceptionClass.take(200),
                message = message?.take(1000),
                stackTrace = stackTrace?.take(properties.errors.maxStackTraceChars),
                httpMethod = httpMethod?.take(10),
                path = path?.take(300),
                httpStatus = httpStatus,
                correlationId = correlationId,
                occurredAt = Instant.now(),
                appVersion = appVersion?.take(40),
                userAgent = userAgent?.take(300),
                context = context?.let { serialize(it) }
            )

            persist(event, fingerprinter.titleFor(exceptionClass, message, stackTrace))
        } catch (ex: Exception) {
            // Recording an error must never itself become an error the user sees.
            log.warn("Nie udało się zapisać zdarzenia błędu ({}): {}", exceptionClass, ex.message)
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString().take(properties.errors.maxStackTraceChars)
    }

    /** The correlation id already put in MDC by `CorrelationIdFilter` on every request. */
    private fun currentCorrelationId(): UUID? = try {
        MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID)?.let(UUID::fromString)
    } catch (_: Exception) {
        null
    }

    private fun serialize(context: Map<String, Any?>): String? = try {
        objectMapper.writeValueAsString(context)
    } catch (_: Exception) {
        null
    }
}
