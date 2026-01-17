package pl.detailing.crm.visit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.transitions.markready.*
import pl.detailing.crm.visit.transitions.complete.*
import pl.detailing.crm.visit.transitions.reject.*
import pl.detailing.crm.visit.transitions.archive.*

/**
 * REST Controller for visit state transitions
 *
 * Provides dedicated endpoints for each state transition:
 * - Mark as ready for pickup
 * - Complete (hand over to customer)
 * - Reject
 * - Archive
 */
@RestController
@RequestMapping("/api/visits")
class VisitTransitionController(
    private val markVisitReadyForPickupHandler: MarkVisitReadyForPickupHandler,
    private val completeVisitHandler: CompleteVisitHandler,
    private val rejectVisitHandler: RejectVisitHandler,
    private val archiveVisitHandler: ArchiveVisitHandler
) {

    /**
     * Mark visit as ready for pickup
     * POST /api/visits/{visitId}/mark-ready-for-pickup
     *
     * Transition: IN_PROGRESS → READY_FOR_PICKUP
     * Requires: All services must be completed or rejected
     * Access: OWNER, MANAGER
     */
    @PostMapping("/{visitId}/mark-ready-for-pickup")
    fun markReadyForPickup(
        @PathVariable visitId: String,
        @RequestBody request: MarkReadyForPickupRequest
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can mark visit as ready
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can mark visit as ready for pickup")
        }

        val command = MarkVisitReadyForPickupCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId),
            sendSms = request.sms,
            sendEmail = request.email
        )

        val result = markVisitReadyForPickupHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit marked as ready for pickup"
        ))
    }

    /**
     * Complete visit - hand over vehicle to customer
     * POST /api/visits/{visitId}/complete
     *
     * Transition: READY_FOR_PICKUP → COMPLETED
     * Access: OWNER, MANAGER
     */
    @PostMapping("/{visitId}/complete")
    fun completeVisit(
        @PathVariable visitId: String
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can complete visit
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can complete visit")
        }

        val command = CompleteVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId)
        )

        val result = completeVisitHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit completed successfully"
        ))
    }

    /**
     * Reject visit
     * POST /api/visits/{visitId}/reject
     *
     * Transition: IN_PROGRESS → REJECTED
     * Access: OWNER, MANAGER
     */
    @PostMapping("/{visitId}/reject")
    fun rejectVisit(
        @PathVariable visitId: String,
        @RequestBody request: RejectVisitRequest
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can reject visit
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can reject visit")
        }

        val command = RejectVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId),
            rejectionReason = request.reason
        )

        val result = rejectVisitHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit rejected"
        ))
    }

    /**
     * Archive visit
     * POST /api/visits/{visitId}/archive
     *
     * Transition: COMPLETED → ARCHIVED or REJECTED → ARCHIVED
     * Access: OWNER
     */
    @PostMapping("/{visitId}/archive")
    fun archiveVisit(
        @PathVariable visitId: String
    ): ResponseEntity<VisitStatusChangeResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER can archive visit
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can archive visit")
        }

        val command = ArchiveVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId)
        )

        val result = archiveVisitHandler.handle(command)

        ResponseEntity.ok(VisitStatusChangeResponse(
            visitId = result.visitId.value.toString(),
            newStatus = mapVisitStatus(result.newStatus),
            message = "Visit archived"
        ))
    }

    /**
     * Map VisitStatus enum to frontend string
     */
    private fun mapVisitStatus(status: VisitStatus): String {
        return when (status) {
            VisitStatus.IN_PROGRESS -> "in_progress"
            VisitStatus.READY_FOR_PICKUP -> "ready_for_pickup"
            VisitStatus.COMPLETED -> "completed"
            VisitStatus.REJECTED -> "rejected"
            VisitStatus.ARCHIVED -> "archived"
        }
    }
}

/**
 * Request to mark visit as ready for pickup with notification preferences
 */
data class MarkReadyForPickupRequest(
    val sms: Boolean,
    val email: Boolean
)

/**
 * Request to reject visit with optional reason
 */
data class RejectVisitRequest(
    val reason: String?
)

/**
 * Response for status change operations
 */
data class VisitStatusChangeResponse(
    val visitId: String,
    val newStatus: String,
    val message: String
)
