package pl.detailing.crm.visit.convert

import pl.detailing.crm.shared.*

/**
 * Command to convert an appointment to a visit
 */
data class ConvertToVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val appointmentId: AppointmentId,
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean,
    val documentsHandedOver: Boolean,
    val technicalNotes: String?
)

/**
 * Request DTO for converting appointment to visit
 */
data class ConvertToVisitRequest(
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean,
    val documentsHandedOver: Boolean,
    val technicalNotes: String?
)

/**
 * Response DTO after successful conversion
 */
data class ConvertToVisitResponse(
    val visitId: String,
    val visitNumber: String,
    val status: String
)

/**
 * Result of the conversion operation
 */
data class ConvertToVisitResult(
    val visitId: VisitId,
    val visitNumber: String
)
