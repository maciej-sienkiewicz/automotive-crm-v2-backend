package pl.detailing.crm.visit.convert

import pl.detailing.crm.appointment.domain.Appointment
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle

/**
 * Validation context for converting appointment to visit
 */
data class ConvertToVisitValidationContext(
    val studioId: StudioId,
    val appointmentId: AppointmentId,
    val mileageAtArrival: Long?,
    val appointment: Appointment?,
    val vehicle: Vehicle?,
    val customer: Customer?,
    val visitAlreadyExists: Boolean
)
