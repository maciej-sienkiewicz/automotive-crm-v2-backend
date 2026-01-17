package pl.detailing.crm.checkin

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*

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
                CustomerData(
                    id = customerReq.id?.let { CustomerId.fromString(it) },
                    firstName = customerReq.firstName,
                    lastName = customerReq.lastName,
                    phone = customerReq.phone,
                    email = customerReq.email,
                    homeAddress = customerReq.homeAddress,
                    company = customerReq.company,
                    isNew = customerReq.isNew
                )
            },
            customerAlias = request.customerAlias,
            vehicle = VehicleData(
                id = request.vehicle.id?.let { VehicleId.fromString(it) },
                brand = request.vehicle.brand,
                model = request.vehicle.model,
                yearOfProduction = request.vehicle.yearOfProduction,
                licensePlate = request.vehicle.licensePlate,
                vin = request.vehicle.vin,
                color = request.vehicle.color,
                paintType = request.vehicle.paintType,
                isNew = request.vehicle.isNew
            ),
            technicalState = request.technicalState,
            photoIds = request.photoIds,
            services = request.services
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
    val services: List<ServiceLineItemRequest>
)

data class CustomerRequest(
    val id: String?,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String,
    val homeAddress: HomeAddressRequest?,
    val company: CompanyRequest?,
    val isNew: Boolean
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
    val regon: String,
    val address: CompanyAddressRequest
)

data class CompanyAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class VehicleRequest(
    val id: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int,
    val licensePlate: String?,
    val vin: String?,
    val color: String?,
    val paintType: String?,
    val isNew: Boolean
)

data class TechnicalStateRequest(
    val mileage: Int,
    val deposit: DepositItemRequest,
    val inspectionNotes: String
)

data class DepositItemRequest(
    val keys: Boolean,
    val registrationDocument: Boolean
)

data class ServiceLineItemRequest(
    val id: String,
    val serviceId: String,
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
    val services: List<ServiceLineItemRequest>
)

data class CustomerData(
    val id: CustomerId?,
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String,
    val homeAddress: HomeAddressRequest?,
    val company: CompanyRequest?,
    val isNew: Boolean
)

data class VehicleData(
    val id: VehicleId?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int,
    val licensePlate: String?,
    val vin: String?,
    val color: String?,
    val paintType: String?,
    val isNew: Boolean
)

// Result
data class ReservationToVisitResult(
    val visitId: VisitId
)
