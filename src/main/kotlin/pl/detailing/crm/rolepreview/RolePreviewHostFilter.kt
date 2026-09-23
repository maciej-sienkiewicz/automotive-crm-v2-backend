package pl.detailing.crm.rolepreview

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.studio.domain.StudioKind
import java.time.Instant

/**
 * Wiąże sesje piaskownic podglądu roli z adresem podglądu - i odwrotnie.
 *
 * nginx dokleja [RolePreviewProperties.HOST_HEADER] do każdego żądania pod adresem podglądu
 * i wycina go z żądań pod adresem aplikacji, więc nagłówek mówi, skąd przyszło żądanie.
 * Klient może go sfałszować tylko na swoją niekorzyść: nagłówek wyłącznie zawęża dostęp.
 *
 * - Sesja piaskownicy działa tylko pod adresem podglądu, tylko dopóki piaskownica żyje
 *   (koniec życia, bezczynność) i nie sięga do tego, co tworzy poświadczenia albo wychodzi
 *   poza system (logowanie, PIN, CardDAV, push).
 * - Pod adresem podglądu działa wyłącznie sesja piaskownicy. Bez sesji - tylko wejście
 *   jednorazowym kodem i to, czego aplikacja potrzebuje, zanim ktokolwiek wejdzie.
 * - Sesja studia, którego już nie ma (np. usuniętej piaskownicy), jest martwa wszędzie.
 *
 * Działa po łańcuchu Spring Security (ma już uwierzytelnienie z sesji), przed kontrolerami.
 */
@Component
@Order(-10)
class RolePreviewHostFilter(
    private val studios: RolePreviewStudios,
    private val rolePreviewService: RolePreviewService
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return !(uri.startsWith("/api/") || uri.startsWith("/ws-registry"))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val onPreviewHost = isPreviewHost(request)
        val principal = SecurityContextHolder.getContext().authentication as? UserPrincipal

        if (principal != null) {
            when (studios.kindOf(principal.studioId.value)) {
                null -> {
                    request.getSession(false)?.invalidate()
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Sesja wygasła. Zaloguj się ponownie.")
                }
                StudioKind.ROLE_PREVIEW -> {
                    // Wyłączenie podglądu działa od razu, nie dopiero po wygaśnięciu piaskownic.
                    if (!rolePreviewService.isAvailable()) {
                        request.getSession(false)?.invalidate()
                        return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Podgląd roli jest wyłączony")
                    }
                    if (!onPreviewHost) {
                        return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Sesja podglądu roli działa tylko w oknie podglądu")
                    }
                    // Wejście nowym kodem samo kończy poprzedni podgląd tej przeglądarki -
                    // także taki, który zdążył wygasnąć.
                    if (!isEnter(request)) {
                        val now = Instant.now()
                        val sandbox = rolePreviewService.activeSandbox(principal.studioId, now)
                        if (sandbox == null) {
                            request.getSession(false)?.invalidate()
                            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Podgląd roli wygasł")
                        }
                        if (isDeniedInSandbox(request.requestURI)) {
                            return reject(response, HttpServletResponse.SC_FORBIDDEN, "Ta funkcja nie działa w podglądzie roli")
                        }
                        rolePreviewService.touch(sandbox, now)
                    }
                }
                else -> if (onPreviewHost) {
                    return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Pod tym adresem działa wyłącznie podgląd roli")
                }
            }
        } else if (onPreviewHost && !isAllowedAnonymouslyOnPreviewHost(request)) {
            return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Podgląd roli wygasł albo nie został otwarty")
        }

        chain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write(
            """{"error":"${if (status == HttpServletResponse.SC_FORBIDDEN) "Forbidden" else "Unauthorized"}","message":"$message","timestamp":"${Instant.now()}","code":"ROLE_PREVIEW"}"""
        )
    }

    companion object {
        fun isPreviewHost(request: HttpServletRequest): Boolean =
            request.getHeader(RolePreviewProperties.HOST_HEADER)?.trim() == "1"

        private fun isEnter(request: HttpServletRequest): Boolean =
            request.method == HttpMethod.POST.name() && request.requestURI == "/api/v1/role-preview/enter"

        /**
         * Pod adresem podglądu bez sesji: wejście kodem oraz to, co aplikacja pobiera przy
         * starcie, zanim ktokolwiek wejdzie (słowniki pojazdów, manifest, logo, stan serwera).
         */
        private fun isAllowedAnonymouslyOnPreviewHost(request: HttpServletRequest): Boolean {
            if (isEnter(request)) return true
            if (request.method != HttpMethod.GET.name() && request.method != HttpMethod.HEAD.name()) return false
            val uri = request.requestURI
            return uri == "/api/health" ||
                uri == "/api/v1/pwa/manifest" ||
                uri.startsWith("/api/v1/vehicle-metadata") ||
                uri.startsWith("/api/public/branding/")
        }

        /**
         * Czego sesja piaskownicy nie dotyka, choćby rola na to pozwalała: wszystkiego, co
         * zakłada poświadczenia albo sięga poza system. Wysyłki (SMS, e-mail, KSeF...)
         * zatrzymuje dodatkowo bezpiecznik piaskownicy - tu odcinamy całe ścieżki.
         */
        private val DENIED_IN_SANDBOX = listOf(
            "/api/v1/auth/login",
            "/api/v1/auth/signup",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/demo",
            "/api/v1/pin",
            "/api/v1/push",
            "/api/v1/carddav-setup",
            // Sparowany tablet dostaje własny, długo żyjący token - działałby poza oknem podglądu.
            "/api/v1/tablets/pairing-codes",
            "/api/internal"
        )

        private fun isDeniedInSandbox(uri: String): Boolean =
            DENIED_IN_SANDBOX.any { uri == it || uri.startsWith("$it/") } ||
                // Z piaskownicy nie otwiera się kolejnego podglądu.
                uri == "/api/v1/role-preview" || uri == "/api/v1/role-preview/config"
    }
}
