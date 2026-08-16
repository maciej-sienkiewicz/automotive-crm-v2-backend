package pl.detailing.crm.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.subscription.SubscriptionService
import java.time.Instant

/**
 * Coarse-grained billing gate: blocks every API call of a studio whose billing
 * lifecycle says it is not accessible (NO_PLAN before choosing a plan, EXPIRED).
 * Fine-grained module gating is a separate layer (CapabilityAuthorizationAspect).
 *
 * Path exclusions are configured EXCLUSIVELY in [WebMvcConfig] — this class holds
 * no path list on purpose: two sources of truth for the same rule is how the
 * previous implementation drifted.
 *
 * Failure semantics — FAIL CLOSED:
 *   - [UnauthorizedException]: no authenticated session; pass through so the
 *     security layer produces its regular 401 (billing status is unknowable here).
 *   - [ForbiddenException]: subscription inactive → 403 with a machine-readable code.
 *   - anything else (DB down, Redis down, bug): 503, NEVER a silent allow.
 *     An infrastructure error must not hand out free access to paid software.
 */
@Component
class SubscriptionInterceptor(
    private val subscriptionService: SubscriptionService,
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean = runBlocking {
        try {
            val studioId = SecurityContextHelper.getCurrentStudioId()
            subscriptionService.validateAccess(studioId)
            true
        } catch (e: UnauthorizedException) {
            true
        } catch (e: ForbiddenException) {
            respond(
                response, HttpServletResponse.SC_FORBIDDEN,
                code = "SUBSCRIPTION_INACTIVE",
                error = "Subscription expired",
                message = e.message ?: "Subskrypcja nie jest aktywna"
            )
            false
        } catch (e: Exception) {
            logger.error(
                "Subscription check failed for {} {} — failing closed (503)",
                request.method, request.requestURI, e
            )
            respond(
                response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                code = "SUBSCRIPTION_CHECK_UNAVAILABLE",
                error = "Subscription verification unavailable",
                message = "Nie udało się zweryfikować subskrypcji. Spróbuj ponownie za chwilę."
            )
            false
        }
    }

    private fun respond(
        response: HttpServletResponse,
        status: Int,
        code: String,
        error: String,
        message: String
    ) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            objectMapper.writeValueAsString(
                mapOf(
                    "code" to code,
                    "error" to error,
                    "message" to message,
                    "timestamp" to Instant.now().toString()
                )
            )
        )
    }
}
