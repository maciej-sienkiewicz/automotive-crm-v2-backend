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
    val sendConfirmationSms: Boolean = false,
    val sendReminderSms: Boolean = false,
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
    val updateData: NewVehicleDataRequest? // Reusing the same structure as NewVehicleDataRequest
)

enum class VehicleMode {
    EXISTING,
    NEW,
    UPDATE,
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
