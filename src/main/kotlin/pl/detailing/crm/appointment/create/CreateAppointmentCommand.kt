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
    val userName: String? = null,
    val customer: CustomerIdentity,
    val vehicle: VehicleIdentity,
    val services: List<ServiceLineItemCommand>,
    val schedule: ScheduleCommand,
    val appointmentTitle: String?,
    val appointmentColorId: AppointmentColorId,
    val note: String?,
    val internalNote: String? = null,
    val protocolNote: String? = null,
    val sendReminderSms: Boolean = false,
    val doorToDoor: DoorToDoorAppointmentCommand? = null
)

data class DoorToDoorAppointmentCommand(
    val pickupCity: String,
    val pickupStreet: String,
    val deliveryCity: String,
    val deliveryStreet: String,
    val notes: String? = null
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

/** How to resolve ownership when linking an existing vehicle to the appointment's customer. */
enum class VehicleOwnershipAction {
    /** Keep current owners, add this customer as CO_OWNER (spouse arrives with the family car). */
    ADD_CO_OWNER,
    /** Vehicle was sold/handed over: previous owners are unlinked, this customer becomes PRIMARY. */
    TRANSFER_PRIMARY
}

sealed class VehicleIdentity {
    data class Existing(val vehicleId: VehicleId) : VehicleIdentity()
    data class New(
        val brand: String,
        val model: String,
        val year: Int?,
        val licensePlate: String?,
        /**
         * The user saw the plate-collision card for this vehicle id and explicitly chose
         * "it's a different vehicle". Without it a matching plate is rejected with 409
         * VEHICLE_PLATE_EXISTS instead of silently creating a duplicate.
         */
        val duplicateOverrideVehicleId: VehicleId? = null
    ) : VehicleIdentity()
    data class Update(
        val vehicleId: VehicleId,
        val brand: String,
        val model: String,
        val year: Int?,
        val licensePlate: String?
    ) : VehicleIdentity()
    /** Attach a vehicle that already exists in the studio, resolving ownership explicitly. */
    data class LinkExisting(
        val vehicleId: VehicleId,
        val ownership: VehicleOwnershipAction
    ) : VehicleIdentity()
    object None : VehicleIdentity()
}

data class ServiceLineItemCommand(
    val serviceId: ServiceId?,
    val serviceName: String?,
    val basePriceNet: Long,  // Base price in cents
    val vatRate: Int,  // VAT rate percentage
    val adjustmentType: AdjustmentType,
    val adjustmentValue: Double,  // Double to support decimal percentages
    val customNote: String?
)

data class ScheduleCommand(
    val isAllDay: Boolean,
    val startDateTime: Instant,
    val endDateTime: Instant
)
