package pl.detailing.crm.visit.services

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.get.MoneyAmountResponse
import pl.detailing.crm.visit.services.sms.ServiceChangeSmsDraftHandler
import pl.detailing.crm.visit.services.sms.ServiceChangeSmsDraftResponse

/**
 * Controller for visit service operations (approve/reject changes)
 */
@RestController
@RequestMapping("/api/visits")
@RequiresPermission(Permission.VISITS_CREATE)
class VisitServiceOperationsController(
    private val approveServiceHandler: ApproveServiceHandler,
    private val rejectServiceHandler: RejectServiceHandler,
    private val serviceChangeSmsDraftHandler: ServiceChangeSmsDraftHandler
) {

    /**
     * Propozycja treści SMS-a podsumowującego planowane zmiany w usługach.
     * POST /api/visits/{visitId}/services/sms-draft
     *
     * Nic nie zapisuje — liczy skutki [payload] i zwraca treść do edycji w CRM-ie.
     */
    @PostMapping("/{visitId}/services/sms-draft")
    fun draftServiceChangeSms(
        @PathVariable visitId: String,
        @RequestBody payload: ServicesChangesPayload
    ): ResponseEntity<ServiceChangeSmsDraftResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        ResponseEntity.ok(
            serviceChangeSmsDraftHandler.handle(
                visitId = VisitId.fromString(visitId),
                studioId = principal.studioId,
                userId = principal.userId,
                payload = payload
            )
        )
    }

    /**
     * Approve a pending service change
     * POST /api/visits/{visitId}/services/{serviceItemId}/approve
     */
    @PostMapping("/{visitId}/services/{serviceItemId}/approve")
    fun approveService(
        @PathVariable visitId: String,
        @PathVariable serviceItemId: String
    ): ResponseEntity<MoneyAmountResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val totalCost = approveServiceHandler.handle(
            visitId = VisitId.fromString(visitId),
            serviceItemId = VisitServiceItemId.fromString(serviceItemId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        ResponseEntity.ok(totalCost)
    }

    /**
     * Reject a pending service change
     * POST /api/visits/{visitId}/services/{serviceItemId}/reject
     */
    @PostMapping("/{visitId}/services/{serviceItemId}/reject")
    fun rejectService(
        @PathVariable visitId: String,
        @PathVariable serviceItemId: String
    ): ResponseEntity<MoneyAmountResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val totalCost = rejectServiceHandler.handle(
            visitId = VisitId.fromString(visitId),
            serviceItemId = VisitServiceItemId.fromString(serviceItemId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        ResponseEntity.ok(totalCost)
    }
}
