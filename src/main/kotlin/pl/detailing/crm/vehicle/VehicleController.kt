package pl.detailing.crm.vehicle

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.create.CreateVehicleCommand
import pl.detailing.crm.vehicle.create.CreateVehicleHandler
import pl.detailing.crm.vehicle.create.CreateVehicleRequest
import pl.detailing.crm.vehicle.get.GetVehicleDetailCommand
import pl.detailing.crm.vehicle.get.GetVehicleDetailHandler
import pl.detailing.crm.vehicle.list.ListVehiclesHandler
import pl.detailing.crm.vehicle.list.VehicleListItem
import pl.detailing.crm.vehicle.owner.AssignOwnerCommand
import pl.detailing.crm.vehicle.owner.AssignOwnerHandler
import pl.detailing.crm.vehicle.owner.RemoveOwnerCommand
import pl.detailing.crm.vehicle.owner.RemoveOwnerHandler
import pl.detailing.crm.vehicle.update.DeleteVehicleCommand
import pl.detailing.crm.vehicle.update.DeleteVehicleHandler
import pl.detailing.crm.vehicle.update.UpdateVehicleCommand
import pl.detailing.crm.vehicle.update.UpdateVehicleHandler
import java.time.Instant

@RestController
@RequestMapping("/api/v1/vehicles")
class VehicleController(
    private val createVehicleHandler: CreateVehicleHandler,
    private val listVehiclesHandler: ListVehiclesHandler,
    private val getVehicleDetailHandler: GetVehicleDetailHandler,
    private val updateVehicleHandler: UpdateVehicleHandler,
    private val deleteVehicleHandler: DeleteVehicleHandler,
    private val assignOwnerHandler: AssignOwnerHandler,
    private val removeOwnerHandler: RemoveOwnerHandler
) {

    @GetMapping
    fun getVehicles(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String,
        @RequestParam(required = false) status: String?
    ): ResponseEntity<VehicleListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        var vehicles = listVehiclesHandler.handle(principal.studioId)

        // Filter by search
        if (search.isNotBlank()) {
            vehicles = vehicles.filter {
                it.licensePlate.contains(search, ignoreCase = true) ||
                it.brand.contains(search, ignoreCase = true) ||
                it.model.contains(search, ignoreCase = true) ||
                it.owners.any { owner -> owner.customerName.contains(search, ignoreCase = true) }
            }
        }

        // Filter by status
        if (!status.isNullOrBlank()) {
            vehicles = vehicles.filter { it.status == status.lowercase() }
        }

        // Sort
        vehicles = when (sortBy) {
            "licensePlate" -> if (sortDirection == "asc") {
                vehicles.sortedBy { it.licensePlate }
            } else {
                vehicles.sortedByDescending { it.licensePlate }
            }
            "brand" -> if (sortDirection == "asc") {
                vehicles.sortedBy { it.brand }
            } else {
                vehicles.sortedByDescending { it.brand }
            }
            "lastVisitDate" -> if (sortDirection == "asc") {
                vehicles.sortedBy { it.stats.lastVisitDate }
            } else {
                vehicles.sortedByDescending { it.stats.lastVisitDate }
            }
            "totalVisits" -> if (sortDirection == "asc") {
                vehicles.sortedBy { it.stats.totalVisits }
            } else {
                vehicles.sortedByDescending { it.stats.totalVisits }
            }
            "totalSpent" -> if (sortDirection == "asc") {
                vehicles.sortedBy { it.stats.totalSpent.grossAmount }
            } else {
                vehicles.sortedByDescending { it.stats.totalSpent.grossAmount }
            }
            "createdAt" -> vehicles // already sorted by default
            else -> vehicles.sortedBy { it.licensePlate }
        }

        // Paginate
        val totalItems = vehicles.size
        val start = (page - 1) * limit
        val end = minOf(start + limit, totalItems)
        val paginatedVehicles = if (start < totalItems) {
            vehicles.subList(start, end)
        } else {
            emptyList()
        }

        ResponseEntity.ok(VehicleListResponse(
            data = paginatedVehicles,
            pagination = PaginationMeta(
                currentPage = page,
                totalPages = (totalItems + limit - 1) / limit,
                totalItems = totalItems,
                itemsPerPage = limit
            )
        ))
    }

    @GetMapping("/{vehicleId}")
    fun getVehicleDetail(@PathVariable vehicleId: String): ResponseEntity<VehicleDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehicleDetailCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId
        )

        val result = getVehicleDetailHandler.handle(command)

        ResponseEntity.ok(VehicleDetailResponse(
            vehicle = VehicleFullResponse(
                id = result.vehicle.id,
                licensePlate = result.vehicle.licensePlate,
                brand = result.vehicle.brand,
                model = result.vehicle.model,
                yearOfProduction = result.vehicle.yearOfProduction,
                color = result.vehicle.color,
                paintType = result.vehicle.paintType,
                currentMileage = result.vehicle.currentMileage,
                status = result.vehicle.status,
                technicalNotes = result.vehicle.technicalNotes,
                owners = result.vehicle.owners.map { owner ->
                    OwnerResponse(
                        customerId = owner.customerId,
                        customerName = owner.customerName,
                        role = owner.role,
                        assignedAt = owner.assignedAt
                    )
                },
                stats = StatsResponse(
                    totalVisits = result.vehicle.stats.totalVisits,
                    lastVisitDate = result.vehicle.stats.lastVisitDate,
                    totalSpent = MoneyResponse(
                        netAmount = result.vehicle.stats.totalSpent.netAmount.toDouble(),
                        grossAmount = result.vehicle.stats.totalSpent.grossAmount.toDouble(),
                        currency = result.vehicle.stats.totalSpent.currency
                    )
                ),
                createdAt = result.vehicle.createdAt,
                updatedAt = result.vehicle.updatedAt,
                deletedAt = result.vehicle.deletedAt
            ),
            recentVisits = result.recentVisits.map { visit ->
                VisitSummaryResponse(
                    id = visit.id,
                    date = visit.date,
                    type = visit.type,
                    description = visit.description,
                    status = visit.status,
                    totalCost = MoneyResponse(
                        netAmount = visit.totalCost.netAmount.toDouble(),
                        grossAmount = visit.totalCost.grossAmount.toDouble(),
                        currency = visit.totalCost.currency
                    ),
                    technician = visit.technician
                )
            },
            activities = result.activities.map { activity ->
                ActivityResponse(
                    id = activity.id,
                    vehicleId = activity.vehicleId,
                    type = activity.type,
                    description = activity.description,
                    performedBy = activity.performedBy,
                    performedAt = activity.performedAt,
                    metadata = activity.metadata
                )
            },
            photos = result.photos.map { photo ->
                PhotoResponse(
                    id = photo.id,
                    vehicleId = photo.vehicleId,
                    photoUrl = photo.photoUrl,
                    thumbnailUrl = photo.thumbnailUrl,
                    description = photo.description,
                    capturedAt = photo.capturedAt,
                    uploadedAt = photo.uploadedAt,
                    visitId = photo.visitId
                )
            }
        ))
    }

    @PostMapping
    fun createVehicle(@RequestBody request: CreateVehicleRequest): ResponseEntity<VehicleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create vehicles")
        }

        val command = CreateVehicleCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            ownerIds = request.ownerIds.map { CustomerId.fromString(it) },
            licensePlate = request.licensePlate,
            brand = request.brand,
            model = request.model,
            yearOfProduction = request.yearOfProduction,
            color = request.color,
            paintType = request.paintType,
            currentMileage = request.currentMileage
        )

        val result = createVehicleHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(VehicleResponse(
                data = VehicleDataResponse(
                    id = result.vehicleId.toString(),
                    licensePlate = result.licensePlate,
                    brand = result.brand,
                    model = result.model,
                    yearOfProduction = result.yearOfProduction,
                    color = result.color,
                    paintType = result.paintType,
                    currentMileage = result.currentMileage.toLong(),
                    status = result.status.name.lowercase(),
                    ownerIds = result.ownerIds.map { it.toString() },
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            ))
    }

    @PatchMapping("/{vehicleId}")
    fun updateVehicle(
        @PathVariable vehicleId: String,
        @RequestBody request: UpdateVehicleRequest
    ): ResponseEntity<UpdateVehicleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update vehicles")
        }

        val command = UpdateVehicleCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId,
            licensePlate = request.licensePlate,
            color = request.color,
            paintType = request.paintType,
            currentMileage = request.currentMileage?.toInt(),
            status = request.status?.let { VehicleStatus.valueOf(it.uppercase()) }
        )

        val result = updateVehicleHandler.handle(command)

        ResponseEntity.ok(UpdateVehicleResponse(
            data = UpdateVehicleDataResponse(
                id = result.id,
                licensePlate = result.licensePlate,
                brand = result.brand,
                model = result.model,
                yearOfProduction = result.yearOfProduction,
                color = result.color,
                paintType = result.paintType,
                currentMileage = result.currentMileage,
                status = result.status,
                updatedAt = result.updatedAt
            )
        ))
    }

    @DeleteMapping("/{vehicleId}")
    fun deleteVehicle(@PathVariable vehicleId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can delete vehicles")
        }

        val command = DeleteVehicleCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId
        )

        deleteVehicleHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    @PostMapping("/{vehicleId}/owners")
    fun assignOwner(
        @PathVariable vehicleId: String,
        @RequestBody request: AssignOwnerRequest
    ): ResponseEntity<AssignOwnerResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can assign owners")
        }

        val command = AssignOwnerCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            customerId = CustomerId.fromString(request.customerId),
            role = OwnershipRole.valueOf(request.role.uppercase())
        )

        val result = assignOwnerHandler.handle(command)

        ResponseEntity.ok(AssignOwnerResponse(
            data = OwnerAssignmentData(
                vehicleId = result.vehicleId,
                customerId = result.customerId,
                role = result.role,
                assignedAt = result.assignedAt
            )
        ))
    }

    @DeleteMapping("/{vehicleId}/owners/{customerId}")
    fun removeOwner(
        @PathVariable vehicleId: String,
        @PathVariable customerId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can remove owners")
        }

        val command = RemoveOwnerCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            customerId = CustomerId.fromString(customerId)
        )

        removeOwnerHandler.handle(command)

        ResponseEntity.noContent().build()
    }
}

// Response DTOs
data class VehicleListResponse(
    val data: List<VehicleListItem>,
    val pagination: PaginationMeta
)

data class PaginationMeta(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)

data class VehicleResponse(
    val data: VehicleDataResponse
)

data class VehicleDataResponse(
    val id: String,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Long,
    val status: String,
    val ownerIds: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class VehicleDetailResponse(
    val vehicle: VehicleFullResponse,
    val recentVisits: List<VisitSummaryResponse>,
    val activities: List<ActivityResponse>,
    val photos: List<PhotoResponse>
)

data class VehicleFullResponse(
    val id: String,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Long?,
    val status: String,
    val technicalNotes: String,
    val owners: List<OwnerResponse>,
    val stats: StatsResponse,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?
)

data class OwnerResponse(
    val customerId: String,
    val customerName: String,
    val role: String,
    val assignedAt: Instant
)

data class StatsResponse(
    val totalVisits: Int,
    val lastVisitDate: Instant?,
    val totalSpent: MoneyResponse
)

data class MoneyResponse(
    val netAmount: Double,
    val grossAmount: Double,
    val currency: String
)

data class VisitSummaryResponse(
    val id: String,
    val date: Instant,
    val type: String,
    val description: String,
    val status: String,
    val totalCost: MoneyResponse,
    val technician: String
)

data class ActivityResponse(
    val id: String,
    val vehicleId: String,
    val type: String,
    val description: String,
    val performedBy: String,
    val performedAt: Instant,
    val metadata: Map<String, Any>
)

data class PhotoResponse(
    val id: String,
    val vehicleId: String,
    val photoUrl: String,
    val thumbnailUrl: String,
    val description: String,
    val capturedAt: Instant,
    val uploadedAt: Instant,
    val visitId: String?
)

data class UpdateVehicleRequest(
    val licensePlate: String?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Long?,
    val status: String?
)

data class UpdateVehicleResponse(
    val data: UpdateVehicleDataResponse
)

data class UpdateVehicleDataResponse(
    val id: String,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Long,
    val status: String,
    val updatedAt: Instant
)

data class AssignOwnerRequest(
    val customerId: String,
    val role: String
)

data class AssignOwnerResponse(
    val data: OwnerAssignmentData
)

data class OwnerAssignmentData(
    val vehicleId: String,
    val customerId: String,
    val role: String,
    val assignedAt: Instant
)
