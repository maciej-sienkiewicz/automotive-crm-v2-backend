package pl.detailing.crm.user.presence

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import pl.detailing.crm.auth.UserPrincipal

/**
 * Notuje aktywność zalogowanego użytkownika (patrz [UserPresenceService]). Nigdy nie
 * zatrzymuje zapytania: zawsze zwraca true, a bez sesji (tablet, CardDAV, webhooki)
 * po prostu nic nie robi.
 */
@Component
class UserPresenceInterceptor(
    private val presence: UserPresenceService
) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // UserPrincipal sam jest obiektem Authentication (patrz AuthController.login).
        (SecurityContextHolder.getContext().authentication as? UserPrincipal)?.let { presence.touch(it.userId.value) }
        return true
    }
}
