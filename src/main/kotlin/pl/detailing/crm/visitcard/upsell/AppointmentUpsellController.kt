package pl.detailing.crm.visitcard.upsell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.AppointmentId
import java.util.UUID

/**
 * Employee-facing management of upsell suggestions per reservation (appointment),
 * mirroring [VisitUpsellController]. Suggestions attached to a reservation stay
 * visible on the card after check-in converts it into a visit.
 */
@RestController
@RequestMapping("/api/appointments/{appointmentId}/upsell-suggestions")
class AppointmentUpsellController(
    private val adminService: VisitUpsellAdminService
) {

    @GetMapping
    @RequiresPermission(Permission.VISITS_VIEW)
    fun list(@PathVariable appointmentId: String): ResponseEntity<List<UpsellSuggestionResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            ResponseEntity.ok(
                adminService.listForAppointment(AppointmentId.fromString(appointmentId), principal.studioId)
            )
        }
    }

    @PostMapping
    @RequiresPermission(Permission.VISITS_SERVICE_PRICES_EDIT)
    fun create(
        @PathVariable appointmentId: String,
        @RequestBody request: CreateUpsellSuggestionRequest
    ): ResponseEntity<UpsellSuggestionResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            ResponseEntity.ok(
                adminService.createForAppointment(
                    AppointmentId.fromString(appointmentId), principal.studioId, principal.userId, request
                )
            )
        }
    }

    @DeleteMapping("/{suggestionId}")
    @RequiresPermission(Permission.VISITS_SERVICE_PRICES_EDIT)
    fun delete(
        @PathVariable appointmentId: String,
        @PathVariable suggestionId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            adminService.deleteForAppointment(
                AppointmentId.fromString(appointmentId), UUID.fromString(suggestionId), principal.studioId
            )
            ResponseEntity.noContent().build()
        }
    }
}
