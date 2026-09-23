package pl.detailing.crm.rolepreview

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.NotFoundException

/**
 * Otwieranie podglądu roli z prawdziwego studia - z ustawień ról, więc z tym samym
 * uprawnieniem co edycja ról.
 */
@RequiresPermission(Permission.EMPLOYEES_MANAGE)
@RestController
@RequestMapping("/api/v1/role-preview")
class RolePreviewController(
    private val rolePreviewService: RolePreviewService
) {
    /** Czy podgląd jest włączony i pod jakim adresem działa - przycisk pokazuje się tylko wtedy. */
    @GetMapping("/config")
    fun config(): ResponseEntity<RolePreviewConfigResponse> = ResponseEntity.ok(rolePreviewService.config())

    @PostMapping
    fun start(@RequestBody request: StartRolePreviewRequest): ResponseEntity<StartRolePreviewResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.status(HttpStatus.CREATED).body(rolePreviewService.start(principal, request))
    }
}

/**
 * Okno podglądu - wyłącznie pod adresem podglądu. Wejście jednorazowym kodem jest publiczne
 * (to ono zakłada sesję), reszta wymaga sesji pracownika piaskownicy i nie przyjmuje żadnych
 * identyfikatorów: zawsze działa na piaskownicy, do której należy sesja.
 */
@RestController
@RequestMapping("/api/v1/role-preview")
class RolePreviewSandboxController(
    private val rolePreviewService: RolePreviewService
) {
    @PostMapping("/enter")
    fun enter(
        @RequestBody body: EnterRolePreviewRequest,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<EnterRolePreviewResponse> {
        // Sesja piaskownicy powstaje tylko pod adresem podglądu: pod adresem aplikacji
        // nadpisałaby sesję administratora we wszystkich jego kartach.
        if (!RolePreviewHostFilter.isPreviewHost(request) || !rolePreviewService.isAvailable()) {
            throw NotFoundException("Podgląd roli nie istnieje")
        }
        return ResponseEntity.ok(rolePreviewService.enter(body.entryCode, request, response))
    }

    @RequiresRolePreviewSession
    @GetMapping("/current")
    fun current(): ResponseEntity<RolePreviewStateResponse> =
        ResponseEntity.ok(rolePreviewService.current(SecurityContextHelper.getCurrentUser()))

    @RequiresRolePreviewSession
    @PutMapping("/current/role")
    fun updateRole(@RequestBody body: UpdateRolePreviewRequest): ResponseEntity<RolePreviewStateResponse> = runBlocking {
        ResponseEntity.ok(rolePreviewService.updateRole(SecurityContextHelper.getCurrentUser(), body))
    }

    @RequiresRolePreviewSession
    @DeleteMapping("/current")
    fun end(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        rolePreviewService.end(SecurityContextHelper.getCurrentUser(), request)
        // Pamięć przeglądarki pod adresem podglądu (localStorage, IndexedDB, service worker).
        // Bez "cookies": to czyściłoby ciasteczka całej domeny, także sesję administratora.
        response.setHeader("Clear-Site-Data", "\"storage\"")
        return ResponseEntity.noContent().build()
    }
}
