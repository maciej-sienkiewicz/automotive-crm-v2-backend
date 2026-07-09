package pl.detailing.crm.vehicle

import pl.detailing.crm.shared.pii.Pii
import pl.detailing.crm.shared.pii.PiiAccessContext
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
import pl.detailing.crm.vehicle.list.VehicleListQuery
import pl.detailing.crm.vehicle.owner.AssignOwnerCommand
import pl.detailing.crm.vehicle.owner.AssignOwnerHandler
import pl.detailing.crm.vehicle.owner.RemoveOwnerCommand
import pl.detailing.crm.vehicle.owner.RemoveOwnerHandler
import pl.detailing.crm.vehicle.update.DeleteVehicleCommand
import pl.detailing.crm.vehicle.update.DeleteVehicleHandler
import pl.detailing.crm.vehicle.update.UpdateVehicleCommand
import pl.detailing.crm.vehicle.update.UpdateVehicleHandler
import pl.detailing.crm.vehicle.visits.GetVehicleVisitsCommand
import pl.detailing.crm.vehicle.visits.GetVehicleVisitsHandler
import pl.detailing.crm.vehicle.appointments.GetVehicleAppointmentsCommand
import pl.detailing.crm.vehicle.appointments.GetVehicleAppointmentsHandler
import pl.detailing.crm.vehicle.comments.GetVehicleCommentsCommand
import pl.detailing.crm.vehicle.comments.GetVehicleCommentsHandler
import pl.detailing.crm.vehicle.documents.GetVehicleDocumentsHandler
import pl.detailing.crm.vehicle.documents.GetVehicleDocumentsCommand
import pl.detailing.crm.vehicle.documents.VehicleDocumentItem
import pl.detailing.crm.vehicle.documents.VehicleDocumentService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission

@RestController
@RequestMapping("/api/v1/vehicles")
@RequiresPermission(Permission.VISITS_VIEW)
class VehicleController(
    private val createVehicleHandler: CreateVehicleHandler,
    private val listVehiclesHandler: ListVehiclesHandler,
    private val getVehicleDetailHandler: GetVehicleDetailHandler,
    private val updateVehicleHandler: UpdateVehicleHandler,
    private val deleteVehicleHandler: DeleteVehicleHandler,
    private val assignOwnerHandler: AssignOwnerHandler,
    private val removeOwnerHandler: RemoveOwnerHandler,
    private val getVehicleVisitsHandler: GetVehicleVisitsHandler,
    private val getVehicleAppointmentsHandler: GetVehicleAppointmentsHandler,
    private val getVehicleDocumentsHandler: GetVehicleDocumentsHandler,
    private val vehicleDocumentService: VehicleDocumentService,
    private val getVehicleCommentsHandler: GetVehicleCommentsHandler
) {

    @GetMapping
    fun getVehicles(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) brand: String?,
        @RequestParam(required = false) model: String?,
        @RequestParam(required = false) yearFrom: Int?,
        @RequestParam(required = false) yearTo: Int?,
        @RequestParam(required = false) minVisits: Int?,
        @RequestParam(required = false) maxVisits: Int?,
        @RequestParam(required = false) minRevenue: Double?,
        @RequestParam(required = false) maxRevenue: Double?,
        @RequestParam(required = false) services: List<String>?,
        @RequestParam(required = false) lastServiceWithinDays: Int?,
        @RequestParam(required = false) notServicedSinceDays: Int?
    ): ResponseEntity<VehicleListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val serviceIds = services
            ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

        val query = VehicleListQuery(serviceIds = serviceIds)
        var vehicles = listVehiclesHandler.handle(principal.studioId, query)

        // Filter by search
        if (search.isNotBlank()) {
            val normalizedSearch = search.replace("\\s".toRegex(), "")
            // Oracle guard: owner names are masked without the personal-data permission,
            // so they must not be matchable either — result presence would reveal them.
            val matchOwners = PiiAccessContext.isGranted()
            vehicles = vehicles.filter {
                it.licensePlate.replace("\\s".toRegex(), "").contains(normalizedSearch, ignoreCase = true) ||
                it.brand.contains(search, ignoreCase = true) ||
                it.model.contains(search, ignoreCase = true) ||
                (matchOwners && it.owners.any { owner -> owner.customerName.contains(search, ignoreCase = true) })
            }
        }

        // Filter by status
        if (!status.isNullOrBlank()) {
            vehicles = vehicles.filter { it.status == status.lowercase() }
        }

        // Filter by brand (partial, case-insensitive)
        if (!brand.isNullOrBlank()) {
            vehicles = vehicles.filter { it.brand.contains(brand, ignoreCase = true) }
        }

        // Filter by model (partial, case-insensitive)
        if (!model.isNullOrBlank()) {
            vehicles = vehicles.filter { it.model.contains(model, ignoreCase = true) }
        }

        // Filter by year range
        if (yearFrom != null) {
            vehicles = vehicles.filter { it.yearOfProduction != null && it.yearOfProduction >= yearFrom }
        }

        if (yearTo != null) {
            vehicles = vehicles.filter { it.yearOfProduction != null && it.yearOfProduction <= yearTo }
        }

        // Filter by visit count
        if (minVisits != null) {
            vehicles = vehicles.filter { it.stats.totalVisits >= minVisits }
        }

        if (maxVisits != null) {
            vehicles = vehicles.filter { it.stats.totalVisits <= maxVisits }
        }

        // Filter by revenue
        if (minRevenue != null) {
            vehicles = vehicles.filter { it.stats.totalSpent.grossAmount.toDouble() >= minRevenue }
        }

        if (maxRevenue != null) {
            vehicles = vehicles.filter { it.stats.totalSpent.grossAmount.toDouble() <= maxRevenue }
        }

        // Filter by last service date
        if (lastServiceWithinDays != null) {
            val cutoff = Instant.now().minus(lastServiceWithinDays.toLong(), ChronoUnit.DAYS)
            vehicles = vehicles.filter { vehicle ->
                val lastDate = vehicle.stats.lastVisitDate?.let { runCatching { Instant.parse(it) }.getOrNull() }
                lastDate != null && lastDate.isAfter(cutoff)
            }
        }

        if (notServicedSinceDays != null) {
            val cutoff = Instant.now().minus(notServicedSinceDays.toLong(), ChronoUnit.DAYS)
            vehicles = vehicles.filter { vehicle ->
                val lastDate = vehicle.stats.lastVisitDate?.let { runCatching { Instant.parse(it) }.getOrNull() }
                lastDate == null || lastDate.isBefore(cutoff)
            }
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
                    description = visit.description,
                    status = visit.status,
                    totalCost = MoneyResponse(
                        netAmount = visit.totalCost.netAmount.toDouble(),
                        grossAmount = visit.totalCost.grossAmount.toDouble(),
                        currency = visit.totalCost.currency
                    ),
                    createdBy = visit.createdBy
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
    @RequiresPermission(Permission.VISITS_CREATE)
    fun createVehicle(@RequestBody request: CreateVehicleRequest): ResponseEntity<VehicleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = CreateVehicleCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
    @RequiresPermission(Permission.VISITS_CREATE)
    fun updateVehicle(
        @PathVariable vehicleId: String,
        @RequestBody request: UpdateVehicleRequest
    ): ResponseEntity<UpdateVehicleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = UpdateVehicleCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            licensePlate = request.licensePlate,
            brand = request.brand,
            model = request.model,
            yearOfProduction = request.yearOfProduction,
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
    @RequiresPermission(Permission.CUSTOMERS_DELETE)
    fun deleteVehicle(@PathVariable vehicleId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = DeleteVehicleCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        deleteVehicleHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    @PostMapping("/{vehicleId}/owners")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun assignOwner(
        @PathVariable vehicleId: String,
        @RequestBody request: AssignOwnerRequest
    ): ResponseEntity<AssignOwnerResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = AssignOwnerCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
    @RequiresPermission(Permission.VISITS_CREATE)
    fun removeOwner(
        @PathVariable vehicleId: String,
        @PathVariable customerId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = RemoveOwnerCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            customerId = CustomerId.fromString(customerId)
        )

        removeOwnerHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    @GetMapping("/{vehicleId}/visits")
    fun getVehicleVisits(
        @PathVariable vehicleId: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean
    ): ResponseEntity<VehicleVisitsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehicleVisitsCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            page = page,
            limit = limit,
            includeDeleted = includeDeleted
        )

        val result = getVehicleVisitsHandler.handle(command)

        ResponseEntity.ok(VehicleVisitsResponse(
            visits = result.visits.map { visit ->
                VehicleVisitResponse(
                    id = visit.id,
                    date = visit.date,
                    customerId = visit.customerId,
                    customerName = visit.customerName,
                    description = visit.description,
                    totalCost = MoneyResponse(
                        netAmount = visit.totalCost.netAmount.toDouble(),
                        grossAmount = visit.totalCost.grossAmount.toDouble(),
                        currency = visit.totalCost.currency
                    ),
                    status = visit.status,
                    createdBy = visit.createdBy,
                    notes = visit.notes
                )
            },
            pagination = PaginationMeta(
                currentPage = result.pagination.currentPage,
                totalPages = result.pagination.totalPages,
                totalItems = result.pagination.totalItems,
                itemsPerPage = result.pagination.itemsPerPage
            )
        ))
    }

    @GetMapping("/{vehicleId}/appointments")
    fun getVehicleAppointments(
        @PathVariable vehicleId: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int
    ): ResponseEntity<VehicleAppointmentsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehicleAppointmentsCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            page = page,
            limit = limit
        )

        val result = getVehicleAppointmentsHandler.handle(command)

        ResponseEntity.ok(VehicleAppointmentsResponse(
            appointments = result.appointments.map { appointment ->
                VehicleAppointmentResponse(
                    id = appointment.id,
                    title = appointment.title,
                    customerId = appointment.customerId,
                    customerName = appointment.customerName,
                    startDateTime = appointment.startDateTime,
                    endDateTime = appointment.endDateTime,
                    isAllDay = appointment.isAllDay,
                    status = appointment.status,
                    totalCost = MoneyResponse(
                        netAmount = appointment.totalCost.netAmount.toDouble(),
                        grossAmount = appointment.totalCost.grossAmount.toDouble(),
                        currency = appointment.totalCost.currency
                    ),
                    note = appointment.note,
                    createdAt = appointment.createdAt
                )
            },
            pagination = PaginationMeta(
                currentPage = result.pagination.currentPage,
                totalPages = result.pagination.totalPages,
                totalItems = result.pagination.totalItems,
                itemsPerPage = result.pagination.itemsPerPage
            )
        ))
    }

    @GetMapping("/{vehicleId}/documents")
    @RequiresPermission(Permission.VISITS_DOCUMENTS_MANAGE)
    fun getVehicleDocuments(
        @PathVariable vehicleId: String
    ): ResponseEntity<VehicleDocumentsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehicleDocumentsCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId
        )

        val result = getVehicleDocumentsHandler.handle(command)

        return ResponseEntity.ok(VehicleDocumentsResponse(
            documents = result.documents.map { document ->
                VehicleDocumentResponse(
                    id = document.id,
                    name = document.name,
                    fileName = document.fileName,
                    fileUrl = document.fileUrl,
                    uploadedAt = document.uploadedAt,
                    uploadedByName = document.uploadedByName,
                    source = document.source
                )
            }
        ))
    }

    /**
     * Initiates a document upload for a vehicle.
     * Returns a presigned S3 URL - frontend should PUT the file directly to that URL.
     */
    @PostMapping("/{vehicleId}/documents")
    @RequiresPermission(Permission.VISITS_DOCUMENTS_MANAGE)
    fun initiateDocumentUpload(
        @PathVariable vehicleId: String,
        @RequestBody request: InitiateVehicleDocumentUploadRequest
    ): ResponseEntity<InitiateVehicleDocumentUploadResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = vehicleDocumentService.initiateUpload(
            studioId = principal.studioId.value,
            vehicleId = VehicleId.fromString(vehicleId).value,
            name = request.name,
            fileName = request.fileName,
            contentType = request.contentType,
            uploadedBy = principal.userId.value,
            uploadedByName = principal.fullName
        )

        ResponseEntity.status(HttpStatus.CREATED).body(
            InitiateVehicleDocumentUploadResponse(
                documentId = result.documentId,
                uploadUrl = result.uploadUrl
            )
        )
    }

    @GetMapping("/{vehicleId}/comments")
    fun getVehicleComments(
        @PathVariable vehicleId: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int
    ): ResponseEntity<VehicleCommentsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVehicleCommentsCommand(
            vehicleId = VehicleId.fromString(vehicleId),
            studioId = principal.studioId,
            page = page,
            limit = limit
        )

        val result = getVehicleCommentsHandler.handle(command)

        ResponseEntity.ok(VehicleCommentsResponse(
            comments = result.comments.map { comment ->
                VehicleCommentResponse(
                    id = comment.id,
                    content = comment.content,
                    type = comment.type,
                    createdAt = comment.createdAt,
                    createdBy = comment.createdBy,
                    createdByName = comment.createdByName,
                    visitId = comment.visitId,
                    visitTitle = comment.visitTitle,
                    visitDate = comment.visitDate
                )
            },
            pagination = PaginationMeta(
                currentPage = result.pagination.currentPage,
                totalPages = result.pagination.totalPages,
                totalItems = result.pagination.totalItems,
                itemsPerPage = result.pagination.itemsPerPage
            )
        ))
    }

    @DeleteMapping("/{vehicleId}/documents/{documentId}")
    @RequiresPermission(Permission.VISITS_DOCUMENTS_MANAGE)
    fun deleteVehicleDocument(
        @PathVariable vehicleId: String,
        @PathVariable documentId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        vehicleDocumentService.deleteDocument(
            documentId = java.util.UUID.fromString(documentId),
            studioId = principal.studioId.value,
            deletedBy = principal.userId.value,
            deletedByName = principal.fullName
        )

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
    @Pii val customerName: String,
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
    val description: String,
    val status: String,
    val totalCost: MoneyResponse,
    val createdBy: String
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
    val brand: String?,
    val model: String?,
    val yearOfProduction: Int?,
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

// Vehicle Visits DTOs
data class VehicleVisitsResponse(
    val visits: List<VehicleVisitResponse>,
    val pagination: PaginationMeta
)

data class VehicleVisitResponse(
    val id: String,
    val date: Instant,
    val customerId: String,
    @Pii val customerName: String,
    val description: String,
    val totalCost: MoneyResponse,
    val status: String,
    val createdBy: String,
    val notes: String
)

// Vehicle Appointments DTOs
data class VehicleAppointmentsResponse(
    val appointments: List<VehicleAppointmentResponse>,
    val pagination: PaginationMeta
)

data class VehicleAppointmentResponse(
    val id: String,
    val title: String,
    val customerId: String,
    @Pii val customerName: String,
    val startDateTime: Instant,
    val endDateTime: Instant,
    val isAllDay: Boolean,
    val status: String,
    val totalCost: MoneyResponse,
    val note: String,
    val createdAt: Instant
)

// Vehicle Documents DTOs
data class VehicleDocumentsResponse(
    val documents: List<VehicleDocumentResponse>
)

data class VehicleDocumentResponse(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedByName: String,
    val source: String // "VEHICLE" or "VISIT"
)

data class InitiateVehicleDocumentUploadRequest(
    val name: String,
    val fileName: String,
    val contentType: String
)

data class InitiateVehicleDocumentUploadResponse(
    val documentId: String,
    val uploadUrl: String
)

// Vehicle Comments DTOs
data class VehicleCommentsResponse(
    val comments: List<VehicleCommentResponse>,
    val pagination: PaginationMeta
)

data class VehicleCommentResponse(
    val id: String,
    val content: String,
    val type: String,
    val createdAt: Instant,
    val createdBy: String,
    val createdByName: String,
    val visitId: String,
    val visitTitle: String,
    val visitDate: Instant
)
