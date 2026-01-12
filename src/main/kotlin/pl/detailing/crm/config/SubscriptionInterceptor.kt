package pl.detailing.crm.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.subscription.SubscriptionService

@Component
class SubscriptionInterceptor(
    private val subscriptionService: SubscriptionService
) : HandlerInterceptor {

    private val exemptPaths = setOf(
        "/api/auth/signup",
        "/api/auth/login",
        "/api/auth/logout",
        "/api/auth/me",
        "/api/v1/auth/signup",
        "/api/v1/auth/login",
        "/api/v1/auth/logout",
        "/api/v1/auth/me",
        "/api/health"
    )

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val path = request.requestURI

        if (exemptPaths.contains(path) || path.startsWith("/api/subscription") || path.startsWith("/api/v1/subscription")) {
            return true
        }

        return runBlocking {
            try {
                val studioId = SecurityContextHelper.getCurrentStudioId()
                subscriptionService.validateAccess(studioId)
                true
            } catch (e: UnauthorizedException) {
                return@runBlocking true
            } catch (e: ForbiddenException) {
                response.status = HttpServletResponse.SC_FORBIDDEN
                response.contentType = "application/json"
                response.writer.write("""
                    {
                        "error": "Subscription expired",
                        "message": "${e.message}",
                        "timestamp": "${java.time.Instant.now()}"
                    }
                """.trimIndent())
                false
            } catch (e: Exception) {
                return@runBlocking true
            }
        }
    }
}