package pl.detailing.crm.visit.services

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.get.MoneyAmountResponse

/**
 * Controller for visit service operations (approve/reject changes)
 */
@RestController
@RequestMapping("/api/visits")
class VisitServiceOperationsController(
    private val approveServiceHandler: ApproveServiceHandler,
    private val rejectServiceHandler: RejectServiceHandler
) {

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
