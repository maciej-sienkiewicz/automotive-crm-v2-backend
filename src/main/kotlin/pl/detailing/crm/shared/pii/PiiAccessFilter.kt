package pl.detailing.crm.shared.pii

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService

/**
 * Response header telling the frontend whether personal data in this response is real
 * (`granted`) or irreversibly masked (`masked`). Purely presentational — the client uses
 * it to render blur states; the protection itself already happened server-side.
 */
const val PII_ACCESS_HEADER = "X-Pii-Access"

/**
 * Resolves the personal-data access decision **once per HTTP request** and binds it to the
 * request thread for [PiiMaskingModule]. Runs after the Spring Security chain, so the
 * authenticated [UserPrincipal] (session-based) is already in the [SecurityContextHolder].
 *
 * Decision:
 * - signing-tablet endpoints (`/api/tablet/**`, token-authenticated) are GRANTED by design:
 *   the recipient is the customer physically at the device, confirming their own data;
 * - an authenticated employee is GRANTED iff [PermissionCheckService] confirms
 *   `CUSTOMERS_VIEW_PERSONAL_DATA` (owner always passes, feature gating included);
 * - everything else — webhooks, anonymous endpoints, mobile upload tokens — is MASKED.
 */
@Component
class PiiAccessFilter(
    private val permissionCheckService: PermissionCheckService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val access = resolveAccess(request)
        PiiAccessContext.open(access)
        response.setHeader(PII_ACCESS_HEADER, if (access == PiiAccess.GRANTED) "granted" else "masked")
        try {
            filterChain.doFilter(request, response)
        } finally {
            PiiAccessContext.clear()
        }
    }

    private fun resolveAccess(request: HttpServletRequest): PiiAccess {
        if (request.requestURI.startsWith("/api/tablet/")) return PiiAccess.GRANTED

        val principal = SecurityContextHolder.getContext().authentication as? UserPrincipal
            ?: return PiiAccess.MASKED

        val granted = permissionCheckService.hasPermission(
            principal.userId,
            principal.studioId,
            Permission.CUSTOMERS_VIEW_PERSONAL_DATA
        )
        return if (granted) PiiAccess.GRANTED else PiiAccess.MASKED
    }
}
