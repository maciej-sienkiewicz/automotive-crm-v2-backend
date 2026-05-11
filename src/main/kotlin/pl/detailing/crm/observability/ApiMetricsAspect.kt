package pl.detailing.crm.observability

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import pl.detailing.crm.invoicing.domain.ExternalInvoiceNotFoundException
import pl.detailing.crm.invoicing.domain.InvoicingCredentialsNotFoundException
import pl.detailing.crm.invoicing.domain.InvoicingProviderApiException
import pl.detailing.crm.invoicing.domain.InvoicingProviderNotSupportedException
import pl.detailing.crm.invoicing.domain.InvoicingValidationException
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.ValidationException
import jakarta.servlet.http.HttpServletRequest

/**
 * AOP aspect that automatically instruments every public method inside any
 * class annotated with @RestController.
 *
 * Recorded metrics
 * ─────────────────
 * crm_api_request_duration_seconds  – Timer (count + sum + histogram)
 *   Tags: path, method, status, exception, controller, endpoint, user_role
 *
 * crm_api_errors_total              – Counter, only for 4xx / 5xx responses
 *   Tags: path, method, status, exception, controller, user_role
 *
 * Design notes
 * ─────────────
 * • Status is derived from ResponseEntity return values for successful calls.
 *   For thrown exceptions the same mapping logic used in GlobalExceptionHandler
 *   is applied, giving accurate status codes without coupling the two classes.
 * • The path tag uses Spring's "bestMatchingPattern" request attribute
 *   (/api/v1/customers/{id} instead of /api/v1/customers/42) to keep
 *   Prometheus cardinality bounded.
 * • Controller name, method name and user role are also stored as request
 *   attributes so HttpMetricsFilter can reuse them for response-size tagging.
 */
@Aspect
@Component
class ApiMetricsAspect(private val registry: MeterRegistry) {

    // ── Pointcut: every method inside a @RestController class ────────────────

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    fun restControllerLayer() {
    }

    // ── Around advice ────────────────────────────────────────────────────────

    @Around("restControllerLayer()")
    fun record(joinPoint: ProceedingJoinPoint): Any? {
        val request: HttpServletRequest? = currentRequest()

        val controller = joinPoint.target.javaClass.simpleName
        val endpoint = joinPoint.signature.name
        val userRole = currentUserRole()

        // Expose context so HttpMetricsFilter can pick it up without re-computing
        request?.setAttribute(MetricsTags.ATTR_CONTROLLER, controller)
        request?.setAttribute(MetricsTags.ATTR_ENDPOINT, endpoint)
        request?.setAttribute(MetricsTags.ATTR_USER_ROLE, userRole)

        val path = pathTemplate(request)
        val httpMethod = request?.method ?: MetricsTags.TAG_VALUE_UNKNOWN

        val sample = Timer.start(registry)
        var exceptionName = MetricsTags.TAG_VALUE_NONE
        var httpStatus = "200"

        return try {
            val result = joinPoint.proceed()
            if (result is ResponseEntity<*>) {
                httpStatus = result.statusCode.value().toString()
            }
            result
        } catch (ex: Throwable) {
            exceptionName = ex.javaClass.simpleName
            httpStatus = mapToHttpStatus(ex)
            throw ex
        } finally {
            val finalStatus = httpStatus
            val finalException = exceptionName

            sample.stop(
                Timer.builder(MetricsTags.API_REQUEST_DURATION)
                    .description("CRM API endpoint request duration")
                    .tag(MetricsTags.TAG_PATH, path)
                    .tag(MetricsTags.TAG_METHOD, httpMethod)
                    .tag(MetricsTags.TAG_STATUS, finalStatus)
                    .tag(MetricsTags.TAG_EXCEPTION, finalException)
                    .tag(MetricsTags.TAG_CONTROLLER, controller)
                    .tag(MetricsTags.TAG_ENDPOINT, endpoint)
                    .tag(MetricsTags.TAG_USER_ROLE, userRole)
                    .register(registry)
            )

            if (finalStatus.startsWith("4") || finalStatus.startsWith("5")) {
                registry.counter(
                    MetricsTags.API_ERRORS_TOTAL,
                    MetricsTags.TAG_PATH, path,
                    MetricsTags.TAG_METHOD, httpMethod,
                    MetricsTags.TAG_STATUS, finalStatus,
                    MetricsTags.TAG_EXCEPTION, finalException,
                    MetricsTags.TAG_CONTROLLER, controller,
                    MetricsTags.TAG_USER_ROLE, userRole
                ).increment()
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    /**
     * Returns the URI path *template* set by Spring MVC's HandlerMapping
     * (e.g. /api/v1/customers/{customerId}).  Falls back to the raw request URI
     * only when the attribute is absent (e.g. during error dispatch).
     */
    private fun pathTemplate(request: HttpServletRequest?): String =
        request?.getAttribute("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern")
            ?.toString()
            ?: request?.requestURI
            ?: MetricsTags.TAG_VALUE_UNKNOWN

    private fun currentUserRole(): String {
        val auth = SecurityContextHolder.getContext().authentication
        return auth?.authorities?.firstOrNull()?.authority
            ?: MetricsTags.TAG_VALUE_ANONYMOUS
    }

    /**
     * Mirrors the HTTP status mappings in GlobalExceptionHandler so that
     * error metrics carry the correct status code even before the exception
     * handler has had a chance to write the response.
     */
    private fun mapToHttpStatus(ex: Throwable): String = when (ex) {
        is UnauthorizedException -> "401"
        is org.springframework.security.core.AuthenticationException -> "401"
        is ForbiddenException -> "403"
        is ValidationException -> "400"
        is InvoicingValidationException -> "400"
        is EntityNotFoundException -> "404"
        is NotFoundException -> "404"
        is ExternalInvoiceNotFoundException -> "404"
        is ConflictException -> "409"
        is InsufficientSmsCreditsException -> "402"
        is UnprocessableEntityException -> "422"
        is InvoicingCredentialsNotFoundException -> "422"
        is InvoicingProviderNotSupportedException -> "501"
        is InvoicingProviderApiException -> when (ex.httpStatus) {
            401, 403 -> "422"
            404 -> "404"
            in 500..599 -> "502"
            else -> "422"
        }
        else -> "500"
    }
}
