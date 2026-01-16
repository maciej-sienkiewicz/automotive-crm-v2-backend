package pl.detailing.crm.visit

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.convert.*
import pl.detailing.crm.visit.details.GetVisitDetailsCommand
import pl.detailing.crm.visit.details.GetVisitDetailsHandler
import pl.detailing.crm.visit.services.*

@RestController
@RequestMapping("/api/v1/visits")
class VisitController(
    private val convertToVisitHandler: ConvertToVisitHandler,
    private val getVisitDetailsHandler: GetVisitDetailsHandler,
    private val addServiceHandler: AddServiceHandler,
    private val updateServiceStatusHandler: UpdateServiceStatusHandler
) {

    /**
     * Convert appointment to visit (check-in)
     * POST /api/v1/visits/convert/{appointmentId}
     */
    @PostMapping("/convert/{appointmentId}")
    fun convertAppointmentToVisit(
        @PathVariable appointmentId: String,
        @RequestBody request: ConvertToVisitRequest
    ): ResponseEntity<ConvertToVisitResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can convert appointments
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can convert appointments to visits")
        }

        val command = ConvertToVisitCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            appointmentId = AppointmentId.fromString(appointmentId),
            mileageAtArrival = request.mileageAtArrival,
            keysHandedOver = request.keysHandedOver,
            documentsHandedOver = request.documentsHandedOver,
            technicalNotes = request.technicalNotes
        )

        val result = convertToVisitHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                ConvertToVisitResponse(
                    visitId = result.visitId.value.toString(),
                    visitNumber = result.visitNumber,
                    status = "ACCEPTED"
                )
            )
    }

    /**
     * Get visit details
     * GET /api/v1/visits/{id}
     */
    @GetMapping("/{id}")
    fun getVisitDetails(@PathVariable id: String): ResponseEntity<VisitDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVisitDetailsCommand(
            studioId = principal.studioId,
            visitId = VisitId.fromString(id)
        )

        val result = getVisitDetailsHandler.handle(command)

        // Map to response DTO
        val response = VisitDetailResponse(
            visit = VisitDto(
                id = result.visit.id.value.toString(),
                visitNumber = result.visit.visitNumber,
                status = result.visit.status.name.lowercase(),
                scheduledDate = result.visit.scheduledDate.toString(),
                completedDate = result.visit.completedDate?.toString(),
                vehicle = VehicleInfoDto(
                    id = result.visit.vehicleId.value.toString(),
                    licensePlate = result.visit.licensePlateSnapshot,
                    brand = result.visit.brandSnapshot,
                    model = result.visit.modelSnapshot,
                    yearOfProduction = result.visit.yearOfProductionSnapshot,
                    color = result.visit.colorSnapshot,
                    engineType = result.visit.engineTypeSnapshot.name.lowercase(),
                    currentMileage = result.vehicle.currentMileage.toLong()
                ),
                customer = CustomerInfoDto(
                    id = result.customer.id.value.toString(),
                    firstName = result.customer.firstName,
                    lastName = result.customer.lastName,
                    email = result.customer.email,
                    phone = result.customer.phone,
                    companyName = result.customer.companyData?.name,
                    stats = CustomerStatsDto(
                        totalVisits = 0, // TODO: Calculate from visits
                        totalSpent = MoneyAmountDto(
                            netAmount = 0,
                            grossAmount = 0,
                            currency = "PLN"
                        ),
                        vehiclesCount = 1 // TODO: Calculate from vehicles
                    )
                ),
                services = result.visit.serviceItems.map { item ->
                    ServiceLineItemDto(
                        id = item.id.value.toString(),
                        serviceId = item.serviceId.value.toString(),
                        serviceName = item.serviceName,
                        basePriceNet = item.basePriceNet.amountInCents,
                        vatRate = item.vatRate.rate,
                        adjustment = AdjustmentDto(
                            type = item.adjustmentType.name,
                            value = item.adjustmentValue
                        ),
                        note = item.customNote ?: "",
                        finalPriceNet = item.finalPriceNet.amountInCents,
                        finalPriceGross = item.finalPriceGross.amountInCents,
                        status = item.status.name.lowercase()
                    )
                },
                totalCost = MoneyAmountDto(
                    netAmount = result.visit.calculateTotalNet().amountInCents,
                    grossAmount = result.visit.calculateTotalGross().amountInCents,
                    currency = "PLN"
                ),
                mileageAtArrival = result.visit.mileageAtArrival,
                keysHandedOver = result.visit.keysHandedOver,
                documentsHandedOver = result.visit.documentsHandedOver,
                technicalNotes = result.visit.technicalNotes,
                createdAt = result.visit.createdAt.toString(),
                updatedAt = result.visit.updatedAt.toString()
            ),
            journalEntries = emptyList(), // TODO: Implement journal entries
            documents = emptyList() // TODO: Implement documents
        )

        ResponseEntity.ok(response)
    }

    /**
     * Add service to visit
     * POST /api/v1/visits/{id}/services
     */
    @PostMapping("/{id}/services")
    fun addServiceToVisit(
        @PathVariable id: String,
        @RequestBody request: AddServiceRequest
    ): ResponseEntity<AddServiceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can add services
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can add services to visits")
        }

        val command = AddServiceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(id),
            serviceId = ServiceId.fromString(request.serviceId),
            adjustmentType = AdjustmentType.valueOf(request.adjustmentType),
            adjustmentValue = request.adjustmentValue,
            customNote = request.customNote
        )

        val result = addServiceHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                AddServiceResponse(
                    serviceItemId = result.serviceItemId.value.toString(),
                    status = "PENDING"
                )
            )
    }

    /**
     * Update service status
     * PATCH /api/v1/visits/{id}/services/{serviceId}/status
     */
    @PatchMapping("/{id}/services/{serviceId}/status")
    fun updateServiceStatus(
        @PathVariable id: String,
        @PathVariable serviceId: String,
        @RequestBody request: UpdateServiceStatusRequest
    ): ResponseEntity<UpdateServiceStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can update service status
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update service status")
        }

        val command = UpdateServiceStatusCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(id),
            serviceItemId = VisitServiceItemId.fromString(serviceId),
            newStatus = VisitServiceStatus.valueOf(request.status.uppercase())
        )

        updateServiceStatusHandler.handle(command)

        ResponseEntity.ok(UpdateServiceStatusResponse(success = true))
    }
}

// Response DTOs
data class VisitDetailResponse(
    val visit: VisitDto,
    val journalEntries: List<JournalEntryDto>,
    val documents: List<VisitDocumentDto>
)

data class VisitDto(
    val id: String,
    val visitNumber: String,
    val status: String,
    val scheduledDate: String,
    val completedDate: String?,
    val vehicle: VehicleInfoDto,
    val customer: CustomerInfoDto,
    val services: List<ServiceLineItemDto>,
    val totalCost: MoneyAmountDto,
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean,
    val documentsHandedOver: Boolean,
    val technicalNotes: String?,
    val createdAt: String,
    val updatedAt: String
)

data class VehicleInfoDto(
    val id: String,
    val licensePlate: String,
    val brand: String,
    val model: String,
    val yearOfProduction: Int,
    val color: String?,
    val engineType: String,
    val currentMileage: Long?
)

data class CustomerInfoDto(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val companyName: String?,
    val stats: CustomerStatsDto
)

data class CustomerStatsDto(
    val totalVisits: Int,
    val totalSpent: MoneyAmountDto,
    val vehiclesCount: Int
)

data class ServiceLineItemDto(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: AdjustmentDto,
    val note: String,
    val finalPriceNet: Long,
    val finalPriceGross: Long,
    val status: String? = null
)

data class AdjustmentDto(
    val type: String,
    val value: Long
)

data class MoneyAmountDto(
    val netAmount: Long,
    val grossAmount: Long,
    val currency: String
)

data class JournalEntryDto(
    val id: String,
    val type: String,
    val content: String,
    val createdBy: String,
    val createdAt: String,
    val isDeleted: Boolean
)

data class VisitDocumentDto(
    val id: String,
    val type: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: String,
    val uploadedBy: String,
    val category: String?
)
