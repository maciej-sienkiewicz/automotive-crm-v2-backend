package pl.detailing.crm.visit.services

import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*

/**
 * Command to add a service to a visit
 */
data class AddServiceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val serviceId: ServiceId,
    val adjustmentType: AdjustmentType,
    val adjustmentValue: Long,
    val customNote: String?
)

/**
 * Request DTO for adding service to visit
 */
data class AddServiceRequest(
    val serviceId: String,
    val adjustmentType: String,
    val adjustmentValue: Long,
    val customNote: String?
)

/**
 * Response DTO after adding service
 */
data class AddServiceResponse(
    val serviceItemId: String,
    val status: String
)

/**
 * Result of adding service operation
 */
data class AddServiceResult(
    val serviceItemId: VisitServiceItemId
)
