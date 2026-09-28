package pl.detailing.crm.visit.servicechecks

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

/**
 * Lista kontrolna usług wizyty (ustawienie studia, domyślnie wyłączone).
 *
 * GET /api/visits/{visitId}/service-checks                  — odhaczone usługi
 * PUT /api/visits/{visitId}/service-checks/{serviceItemId}  — { "done": true | false }
 *
 * VISITS_VIEW, jak komentarze i zdjęcia: to dokumentowanie pracy przez ludzi na hali,
 * nie edycja wizyty.
 */
@RestController
@RequestMapping("/api/visits/{visitId}/service-checks")
@RequiresPermission(Permission.VISITS_VIEW)
class VisitServiceChecksController(
    private val service: VisitServiceChecksService
) {

    @GetMapping
    fun list(@PathVariable visitId: String): ResponseEntity<ServiceChecksResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val checks = service.list(principal.studioId, VisitId.fromString(visitId))
        return ResponseEntity.ok(ServiceChecksResponse(checks.map { it.toResponse() }))
    }

    @PutMapping("/{serviceItemId}")
    fun set(
        @PathVariable visitId: String,
        @PathVariable serviceItemId: UUID,
        @RequestBody request: SetServiceCheckRequest
    ): ResponseEntity<SetServiceCheckResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val check = service.set(
            principal.studioId, VisitId.fromString(visitId), serviceItemId, request.done,
            principal.userId, principal.fullName
        )
        return ResponseEntity.ok(SetServiceCheckResponse(serviceItemId.toString(), check != null, check?.toResponse()))
    }

    private fun ServiceCheck.toResponse() = ServiceCheckResponse(serviceItemId.toString(), checkedAt, checkedByName)
}

data class SetServiceCheckRequest(val done: Boolean)

data class ServiceCheckResponse(val serviceItemId: String, val checkedAt: Instant, val checkedByName: String?)

data class ServiceChecksResponse(val checks: List<ServiceCheckResponse>)

data class SetServiceCheckResponse(val serviceItemId: String, val done: Boolean, val check: ServiceCheckResponse?)
