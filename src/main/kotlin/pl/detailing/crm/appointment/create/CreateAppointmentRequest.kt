package pl.detailing.crm.appointment.create

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import pl.detailing.crm.appointment.domain.AdjustmentType

/**
 * HTTP Request DTO for creating an appointment (matches frontend API)
 */
data class CreateAppointmentRequest(
    val customer: CustomerIdentityRequest,
    val vehicle: VehicleIdentityRequest,
    val services: List<ServiceLineItemRequest>,
    val schedule: ScheduleRequest,
    val appointmentTitle: String?,
    val appointmentColorId: String,
    val note: String?,
    /** Visible only inside the CRM, never printed on the pickup protocol. */
    val internalNote: String? = null,
    /** Printed on the vehicle pickup protocol. */
    val protocolNote: String? = null,
    val sendConfirmationSms: Boolean = false,
    val sendReminderSms: Boolean = false,
    /** Send the customer their Visit Card link via SMS right after booking. Requires SMS_EMAIL feature. */
    val sendVisitCard: Boolean = false,
    val doorToDoor: DoorToDoorAppointmentRequest? = null
)

data class DoorToDoorAppointmentRequest(
    val pickupCity: String,
    val pickupStreet: String,
    val deliveryCity: String,
    val deliveryStreet: String,
    val notes: String? = null
)

data class CustomerIdentityRequest(
    val mode: CustomerMode,
    val id: String?,
    @JsonProperty("newData") val newData: NewCustomerDataRequest?,
    @JsonProperty("updateData") val updateData: NewCustomerDataRequest?
)

enum class CustomerMode {
    EXISTING,
    NEW,
    UPDATE
}

data class NewCustomerDataRequest(
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val email: String?,
    val company: CompanyDataRequest?
)

data class CompanyDataRequest(
    val name: String,
    val nip: String,
    val regon: String?,
    val address: String
)

data class VehicleIdentityRequest(
    val mode: VehicleMode,
    val id: String?,
    val newData: NewVehicleDataRequest?,
    val updateData: NewVehicleDataRequest?, // Reusing the same structure as NewVehicleDataRequest
    /** Required for LINK_EXISTING: ADD_CO_OWNER or TRANSFER_PRIMARY. */
    val ownership: String? = null,
    /** For NEW: id of the colliding vehicle the user explicitly dismissed as "a different vehicle". */
    val duplicateOverrideVehicleId: String? = null
)

enum class VehicleMode {
    EXISTING,
    NEW,
    UPDATE,
    /** Attach an existing vehicle (found e.g. by plate lookup) and resolve its ownership. */
    LINK_EXISTING,
    NONE
}

data class NewVehicleDataRequest(
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?
)

data class ServiceLineItemRequest(
    val id: String,
    val serviceId: String?,
    val serviceName: String,
    val basePriceNet: Long,
    // Exact gross from the catalog / user input; null (older clients) = derive from net.
    val basePriceGross: Long? = null,
    val vatRate: Int,
    val adjustment: PriceAdjustmentRequest,
    val note: String?
)

data class PriceAdjustmentRequest(
    val type: AdjustmentType,
    val value: Double  // Double to support decimal percentages like -49.19
)

data class ScheduleRequest(
    val isAllDay: Boolean,
    val startDateTime: Instant, // ISO-8601 format
    val endDateTime: Instant    // ISO-8601 format
)
