package pl.detailing.crm.checkin

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.DamagePoint

@RestController
@RequestMapping("/api/checkin")
class CheckinController(
    private val createVisitFromReservationHandler: CreateVisitFromReservationHandler
    // TODO: Add PhotoUploadSession handlers when implemented
) {

    /**
     * Convert appointment/reservation to visit (check-in)
     * POST /api/checkin/reservation-to-visit
     */
    @PostMapping("/reservation-to-visit")
    fun createVisitFromReservation(
        @RequestBody request: ReservationToVisitRequest
    ): ResponseEntity<ReservationToVisitResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can perform check-in
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can perform vehicle check-in")
        }

        val command = ReservationToVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            reservationId = AppointmentId.fromString(request.reservationId),
            customer = request.customer?.let { customerReq ->
                when (customerReq.mode) {
                    IdentityMode.EXISTING -> CustomerData.Existing(
                        id = CustomerId.fromString(customerReq.id!!)
                    )
                    IdentityMode.NEW -> {
                        val newData = customerReq.newData!!
                        if (newData.firstName.isNullOrBlank() || newData.lastName.isNullOrBlank()) {
                            throw ValidationException("Imię i nazwisko są wymagane podczas przyjmowania pojazdu.")
                        }
                        CustomerData.New(
                            firstName = newData.firstName,
                            lastName = newData.lastName,
                            phone = newData.phone,
                            email = newData.email,
                            homeAddress = newData.homeAddress,
                            company = newData.company
                        )
                    }
                    IdentityMode.UPDATE -> {
                        val updateData = customerReq.updateData!!
                        if (updateData.firstName.isNullOrBlank() || updateData.lastName.isNullOrBlank()) {
                            throw ValidationException("Imię i nazwisko są wymagane podczas przyjmowania pojazdu.")
                        }
                        CustomerData.Update(
                            id = CustomerId.fromString(customerReq.id!!),
                            firstName = updateData.firstName,
                            lastName = updateData.lastName,
                            phone = updateData.phone,
                            email = updateData.email,
                            homeAddress = updateData.homeAddress,
                            company = updateData.company
                        )
                    }
                }
            },
            customerAlias = request.customerAlias,
            vehicle = when (request.vehicle.mode) {
                IdentityMode.EXISTING -> VehicleData.Existing(
                    id = VehicleId.fromString(request.vehicle.id!!)
                )
                IdentityMode.NEW -> {
                    val newData = request.vehicle.newData!!
                    VehicleData.New(
                        brand = newData.brand,
                        model = newData.model,
                        yearOfProduction = newData.yearOfProduction,
                        licensePlate = newData.licensePlate,
                        color = newData.color,
                        paintType = newData.paintType
                    )
                }
                IdentityMode.UPDATE -> {
                    val updateData = request.vehicle.updateData!!
                    VehicleData.Update(
                        id = VehicleId.fromString(request.vehicle.id!!),
                        brand = updateData.brand,
                        model = updateData.model,
                        yearOfProduction = updateData.yearOfProduction,
                        licensePlate = updateData.licensePlate,
                        color = updateData.color,
                        paintType = updateData.paintType
                    )
                }
            },
            technicalState = request.technicalState,
            photoIds = request.photoIds,
            damagePoints = request.damagePoints?.map { damagePointReq ->
                DamagePoint(
                    id = damagePointReq.id,
                    x = damagePointReq.x,
                    y = damagePointReq.y,
                    note = damagePointReq.note
                )
            } ?: emptyList(),
            services = request.services,
            appointmentColorId = request.appointmentColorId?.let { AppointmentColorId.fromString(it) }
        )

        val result = createVisitFromReservationHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ReservationToVisitResponse(visitId = result.visitId.value.toString()))
    }
}

// Request/Response DTOs
data class ReservationToVisitRequest(
    val reservationId: String,
    val customer: CustomerRequest?,
    val customerAlias: String?,
    val vehicle: VehicleRequest,
    val technicalState: TechnicalStateRequest,
    val photoIds: List<String>,
    val damagePoints: List<DamagePointRequest>?,
    val services: List<ServiceLineItemRequest>,
    val appointmentColorId: String?
)

enum class IdentityMode {
    EXISTING, NEW, UPDATE
}

data class CustomerRequest(
    val mode: IdentityMode,
    val id: String?,
    val newData: CustomerDataRequest?,
    val updateData: CustomerDataRequest?
)

data class CustomerDataRequest(
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val email: String?,
    val homeAddress: HomeAddressRequest?,
    val company: CompanyRequest?
)

data class HomeAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyRequest(
    val name: String,
    val nip: String,
    val regon: String?,
    val address: CompanyAddressRequest
)

data class CompanyAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class VehicleRequest(
    val mode: IdentityMode,
    val id: String?,
    val newData: VehicleDataRequest?,
    val updateData: VehicleDataRequest?
)

data class VehicleDataRequest(
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val licensePlate: String?,
    val color: String?,
    val paintType: String?
)

data class TechnicalStateRequest(
    val mileage: Long,
    val deposit: DepositItemRequest,
    val inspectionNotes: String
)

data class DepositItemRequest(
    val keys: Boolean,
    val registrationDocument: Boolean
)

data class DamagePointRequest(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String?
)

data class ServiceLineItemRequest(
    val id: String,
    val serviceId: String?,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: AdjustmentRequest,
    val note: String?
)

data class AdjustmentRequest(
    val type: String,
    val value: Long
)

data class ReservationToVisitResponse(
    val visitId: String
)

// Command for handler
data class ReservationToVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val reservationId: AppointmentId,
    val customer: CustomerData?,
    val customerAlias: String?,
    val vehicle: VehicleData,
    val technicalState: TechnicalStateRequest,
    val photoIds: List<String>,
    val damagePoints: List<DamagePoint>,
    val services: List<ServiceLineItemRequest>,
    val appointmentColorId: AppointmentColorId?
)

sealed class CustomerData {
    data class Existing(val id: CustomerId) : CustomerData()
    data class New(
        val firstName: String,
        val lastName: String,
        val phone: String?,
        val email: String?,
        val homeAddress: HomeAddressRequest?,
        val company: CompanyRequest?
    ) : CustomerData()
    data class Update(
        val id: CustomerId,
        val firstName: String,
        val lastName: String,
        val phone: String?,
        val email: String?,
        val homeAddress: HomeAddressRequest?,
        val company: CompanyRequest?
    ) : CustomerData()
}

sealed class VehicleData {
    data class Existing(val id: VehicleId) : VehicleData()
    data class New(
        val brand: String,
        val model: String,
        val yearOfProduction: Int?,
        val licensePlate: String?,
        val color: String?,
        val paintType: String?
    ) : VehicleData()
    data class Update(
        val id: VehicleId,
        val brand: String,
        val model: String,
        val yearOfProduction: Int?,
        val licensePlate: String?,
        val color: String?,
        val paintType: String?
    ) : VehicleData()
}

// Result
data class ReservationToVisitResult(
    val visitId: VisitId
)
