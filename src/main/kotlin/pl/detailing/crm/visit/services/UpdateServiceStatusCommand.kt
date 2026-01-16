package pl.detailing.crm.visit.services

import pl.detailing.crm.shared.*

/**
 * Command to update service status
 */
data class UpdateServiceStatusCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val serviceItemId: VisitServiceItemId,
    val newStatus: VisitServiceStatus
)

/**
 * Request DTO for updating service status
 */
data class UpdateServiceStatusRequest(
    val status: String
)

/**
 * Response DTO after updating status
 */
data class UpdateServiceStatusResponse(
    val success: Boolean
)
