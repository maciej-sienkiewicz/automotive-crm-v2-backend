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
import pl.detailing.crm.shared.VisitId
import java.util.UUID

/**
 * Employee-facing management of upsell suggestions per visit.
 *
 * Viewing rides on VISITS_VIEW; creating and removing suggestions require
 * VISITS_SERVICE_PRICES_EDIT because a suggestion fixes a price (and possibly
 * a discount) that the customer can accept.
 */
@RestController
@RequestMapping("/api/visits/{visitId}/upsell-suggestions")
class VisitUpsellController(
    private val adminService: VisitUpsellAdminService
) {

    @GetMapping
    @RequiresPermission(Permission.VISITS_VIEW)
    fun list(@PathVariable visitId: String): ResponseEntity<List<UpsellSuggestionResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            ResponseEntity.ok(adminService.list(VisitId.fromString(visitId), principal.studioId))
        }
    }

    @PostMapping
    @RequiresPermission(Permission.VISITS_SERVICE_PRICES_EDIT)
    fun create(
        @PathVariable visitId: String,
        @RequestBody request: CreateUpsellSuggestionRequest
    ): ResponseEntity<UpsellSuggestionResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            ResponseEntity.ok(
                adminService.create(VisitId.fromString(visitId), principal.studioId, principal.userId, request)
            )
        }
    }

    @DeleteMapping("/{suggestionId}")
    @RequiresPermission(Permission.VISITS_SERVICE_PRICES_EDIT)
    fun delete(
        @PathVariable visitId: String,
        @PathVariable suggestionId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            adminService.delete(VisitId.fromString(visitId), UUID.fromString(suggestionId), principal.studioId)
            ResponseEntity.noContent().build()
        }
    }
}
