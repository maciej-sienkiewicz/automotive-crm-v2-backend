package pl.detailing.crm.appointment.create

import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Internal command for creating an appointment
 */
data class CreateAppointmentCommand(
    val studioId: StudioId,
    val userId: UserId,
    val customer: CustomerIdentity,
    val vehicle: VehicleIdentity,
    val services: List<ServiceLineItemCommand>,
    val schedule: ScheduleCommand,
    val appointmentTitle: String?,
    val appointmentColorId: AppointmentColorId,
    val note: String?
)

sealed class CustomerIdentity {
    data class Existing(val customerId: CustomerId) : CustomerIdentity()
    data class New(
        val firstName: String?,
        val lastName: String?,
        val phone: String?,
        val email: String?,
        val companyName: String?,
        val companyNip: String?,
        val companyRegon: String?,
        val companyAddress: String?
    ) : CustomerIdentity()
    data class Update(
        val customerId: CustomerId,
        val firstName: String?,
        val lastName: String?,
        val phone: String?,
        val email: String?,
        val companyName: String?,
        val companyNip: String?,
        val companyRegon: String?,
        val companyAddress: String?
    ) : CustomerIdentity()
}

sealed class VehicleIdentity {
    data class Existing(val vehicleId: VehicleId) : VehicleIdentity()
    data class New(
        val brand: String,
        val model: String,
        val year: Int?,
        val licensePlate: String?
    ) : VehicleIdentity()
    data class Update(
        val vehicleId: VehicleId,
        val brand: String,
        val model: String,
        val year: Int?,
        val licensePlate: String?
    ) : VehicleIdentity()
    object None : VehicleIdentity()
}

data class ServiceLineItemCommand(
    val serviceId: ServiceId?,
    val serviceName: String?,
    val adjustmentType: AdjustmentType,
    val adjustmentValue: Double,  // Double to support decimal percentages
    val customNote: String?
)

data class ScheduleCommand(
    val isAllDay: Boolean,
    val startDateTime: Instant,
    val endDateTime: Instant
)
