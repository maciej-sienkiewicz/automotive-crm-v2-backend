package pl.detailing.crm.appointment.create

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
    val appointmentColorId: String
)

data class CustomerIdentityRequest(
    val mode: CustomerMode,
    val id: String?,
    val newData: NewCustomerDataRequest?,
    val updateData: NewCustomerDataRequest? // Reusing the same structure as NewCustomerDataRequest
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
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: PriceAdjustmentRequest,
    val note: String?
)

data class PriceAdjustmentRequest(
    val type: AdjustmentType,
    val value: Long
)

data class ScheduleRequest(
    val isAllDay: Boolean,
    val startDateTime: Instant, // ISO-8601 format
    val endDateTime: Instant    // ISO-8601 format
)
