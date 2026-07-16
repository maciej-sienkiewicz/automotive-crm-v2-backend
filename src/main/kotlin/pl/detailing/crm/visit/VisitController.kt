package pl.detailing.crm.visit

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.get.*
import pl.detailing.crm.visit.list.*
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.visit.domain.*
import pl.detailing.crm.visit.transitions.confirm.ConfirmVisitCommand
import pl.detailing.crm.visit.transitions.confirm.ConfirmVisitHandler
import pl.detailing.crm.email.visitwelcome.SendVisitWelcomeEmailHandler
import pl.detailing.crm.email.visitwelcome.SendVisitWelcomeEmailCommand
import pl.detailing.crm.email.visitwelcome.WelcomeEmailOptions
import pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitCommand
import pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitHandler
import pl.detailing.crm.visit.delete.DeleteVisitCommand
import pl.detailing.crm.visit.delete.DeleteVisitHandler
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.visit.services.SaveVisitServicesHandler
import pl.detailing.crm.visit.services.ServicesChangesPayload
import pl.detailing.crm.visit.photos.GetVisitPhotosHandler
import pl.detailing.crm.visit.photos.GetVisitPhotosCommand
import pl.detailing.crm.visit.photos.VisitPhotoInfo
import pl.detailing.crm.visit.photos.AddVisitPhotoHandler
import pl.detailing.crm.visit.photos.AddVisitPhotoCommand
import pl.detailing.crm.visit.photos.DeleteVisitPhotoHandler
import pl.detailing.crm.visit.photos.DeleteVisitPhotoCommand
import pl.detailing.crm.visit.title.UpdateVisitTitleHandler
import pl.detailing.crm.visit.title.UpdateVisitTitleCommand
import pl.detailing.crm.visit.schedule.UpdateEstimatedCompletionDateHandler
import pl.detailing.crm.visit.schedule.UpdateEstimatedCompletionDateCommand
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.list.PackageItemDto
import pl.detailing.crm.shared.pii.PiiAccessContext
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.role.domain.Permission
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/visits")
@RequiresPermission(Permission.VISITS_VIEW)
class VisitController(
    private val listVisitsHandler: ListVisitsHandler,
    private val getVisitDetailHandler: GetVisitDetailHandler,
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository,
    private val getVisitPhotosHandler: GetVisitPhotosHandler,
    private val addVisitPhotoHandler: AddVisitPhotoHandler,
    private val deleteVisitPhotoHandler: DeleteVisitPhotoHandler,
    private val saveVisitServicesHandler: SaveVisitServicesHandler,
    private val confirmVisitHandler: ConfirmVisitHandler,
    private val sendVisitWelcomeEmailHandler: SendVisitWelcomeEmailHandler,
    private val sendVisitCardLinkHandler: pl.detailing.crm.visitcard.SendVisitCardLinkHandler,
    private val cancelDraftVisitHandler: CancelDraftVisitHandler,
    private val deleteVisitHandler: DeleteVisitHandler,
    private val updateVisitTitleHandler: UpdateVisitTitleHandler,
    private val updateEstimatedCompletionDateHandler: UpdateEstimatedCompletionDateHandler,
    private val permissionCheckService: PermissionCheckService
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * Get all visits for the studio with pagination and filtering
     * GET /api/visits?page=1&size=20&status=IN_PROGRESS&search=kowalski&scheduledDate=2024-01-15
     */
    @GetMapping
    fun getVisits(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) scheduledDate: String?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean
    ): ResponseEntity<VisitListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val visitStatus = status?.let {
            try {
                VisitStatus.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        val scheduledDateFilter = scheduledDate?.let {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) {
                null
            }
        }

        val command = pl.detailing.crm.visit.list.ListVisitsCommand(
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, size)),
            status = visitStatus,
            searchTerm = search,
            scheduledDate = scheduledDateFilter,
            includeDeleted = includeDeleted,
            includePiiSearch = PiiAccessContext.isGranted()
        )

        val result = listVisitsHandler.handle(command)

        ResponseEntity.ok(VisitListResponse(
            visits = result.items,
            pagination = PaginationMetadata(
                total = result.total,
                page = result.page,
                pageSize = result.pageSize,
                totalPages = result.totalPages
            )
        ))
    }

    /**
     * Get only soft-deleted visits with pagination and filtering
     * GET /api/visits/deleted
     */
    @GetMapping("/deleted")
    fun getDeletedVisits(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) scheduledDate: String?
    ): ResponseEntity<VisitListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val visitStatus = status?.let {
            try { VisitStatus.valueOf(it.uppercase()) } catch (e: IllegalArgumentException) { null }
        }

        val scheduledDateFilter = scheduledDate?.let {
            try { LocalDate.parse(it) } catch (e: Exception) { null }
        }

        val command = pl.detailing.crm.visit.list.ListVisitsCommand(
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, size)),
            status = visitStatus,
            searchTerm = search,
            scheduledDate = scheduledDateFilter,
            includeDeleted = true,
            includePiiSearch = PiiAccessContext.isGranted()
        )

        val result = listVisitsHandler.handle(command)

        ResponseEntity.ok(VisitListResponse(
            visits = result.items,
            pagination = PaginationMetadata(
                total = result.total,
                page = result.page,
                pageSize = result.pageSize,
                totalPages = result.totalPages
            )
        ))
    }

    /**
     * Get visit details by ID
     * GET /api/visits/{visitId}
     */
    @GetMapping("/{visitId}")
    fun getVisitDetail(
        @PathVariable visitId: String
    ): ResponseEntity<VisitDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVisitDetailCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            visitId = VisitId.fromString(visitId)
        )

        val result = getVisitDetailHandler.handle(command)
        val showPrices = permissionCheckService.hasPermission(
            principal.userId, principal.studioId, Permission.VISITS_SERVICE_PRICES_VIEW
        )

        val response = mapToVisitDetailResponse(result, showPrices, result.doorToDoor)

        ResponseEntity.ok(response)
    }

    /**
     * Get visit photos with presigned download URLs
     * GET /api/visits/{visitId}/photos
     */
    @GetMapping("/{visitId}/photos")
    fun getVisitPhotos(
        @PathVariable visitId: String
    ): ResponseEntity<VisitPhotosResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetVisitPhotosCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId
        )

        val result = getVisitPhotosHandler.handle(command)

        ResponseEntity.ok(VisitPhotosResponse(
            photos = result.photos.map { photo ->
                VisitPhotoResponse(
                    id = photo.id,
                    fileName = photo.fileName,
                    description = photo.description,
                    uploadedAt = photo.uploadedAt,
                    thumbnailUrl = photo.thumbnailUrl,
                    fullSizeUrl = photo.fullSizeUrl,
                    tags = photo.tags
                )
            }
        ))
    }

    /**
     * Add a photo to an existing visit
     * POST /api/visits/{visitId}/photos
     */
    @PostMapping("/{visitId}/photos")
    fun addVisitPhoto(
        @PathVariable visitId: String,
        @RequestBody request: AddPhotoRequest
    ): ResponseEntity<AddPhotoResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AddVisitPhotoCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            fileName = request.fileName,
            description = request.description,
            userId = principal.userId,
            userName = principal.fullName
        )

        val result = addVisitPhotoHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(AddPhotoResponse(
            photoId = result.photoId,
            uploadUrl = result.uploadUrl,
            fileId = result.fileId
        ))
    }

    /**
     * Delete a photo from a visit
     * DELETE /api/visits/{visitId}/photos/{photoId}
     */
    @DeleteMapping("/{visitId}/photos/{photoId}")
    @RequiresPermission(Permission.VISITS_MEDIA_DELETE)
    fun deleteVisitPhoto(
        @PathVariable visitId: String,
        @PathVariable photoId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = DeleteVisitPhotoCommand(
            visitId = VisitId.fromString(visitId),
            photoId = VisitPhotoId.fromString(photoId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        deleteVisitPhotoHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Update visit services (add, update, delete)
     * PATCH /api/visits/{visitId}/services/
     */
    @PatchMapping("/{visitId}/services/")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun saveServicesChanges(
        @PathVariable visitId: String,
        @RequestBody payload: ServicesChangesPayload
    ): ResponseEntity<MoneyAmountResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val totalCost = saveVisitServicesHandler.handle(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            payload = payload,
            userName = principal.fullName
        )

        ResponseEntity.ok(totalCost)
    }

    /**
     * Confirm a DRAFT visit and make it active (IN_PROGRESS)
     * POST /api/visits/{visitId}/confirm
     *
     * This endpoint:
     * - Validates all mandatory protocols are signed
     * - Changes visit status from DRAFT to IN_PROGRESS
     * - Updates appointment status from CONFIRMED to CONVERTED
     * - Makes the visit immutable (cannot be cancelled anymore)
     */
    @PostMapping("/{visitId}/confirm")
    @RequiresPermission(Permission.VISITS_CHANGE_STATUS)
    fun confirmVisit(
        @PathVariable visitId: String,
        @RequestBody(required = false) request: ConfirmVisitRequest?
    ): ResponseEntity<ConfirmVisitResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = ConfirmVisitCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        val result = confirmVisitHandler.handle(command)

        val req = request ?: ConfirmVisitRequest()

        if (req.sendEmail) {
            val opts = req.emailOptions ?: ConfirmEmailOptionsRequest()
            runCatching {
                sendVisitWelcomeEmailHandler.handle(
                    SendVisitWelcomeEmailCommand(
                        visitId = command.visitId,
                        studioId = command.studioId,
                        options = WelcomeEmailOptions(
                            attachProtocol = opts.attachProtocol,
                            attachPhotos = opts.attachPhotos,
                            photoIds = opts.photoIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() },
                            attachDamageMap = opts.attachDamageMap
                        )
                    )
                )
            }.onFailure { ex ->
                logger.warn("confirmVisit: email notification failed [visitId={}]: {}", visitId, ex.message)
            }
        }

        // Visit Card link — sent over the studio-configured channel (EMAIL/SMS/BOTH/NONE)
        runCatching {
            sendVisitCardLinkHandler.handle(
                pl.detailing.crm.visitcard.SendVisitCardLinkCommand(
                    visitId = command.visitId,
                    studioId = command.studioId
                )
            )
        }.onFailure { ex ->
            logger.warn("confirmVisit: visit card link send failed [visitId={}]: {}", visitId, ex.message)
        }

        ResponseEntity.ok(ConfirmVisitResponse(
            visitId = result.visitId.value.toString(),
            message = "Visit confirmed successfully"
        ))
    }

    /**
     * Update visit title regardless of status
     * PATCH /api/visits/{visitId}/title
     */
    @PatchMapping("/{visitId}/title")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun updateVisitTitle(
        @PathVariable visitId: String,
        @RequestBody request: UpdateVisitTitleRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateVisitTitleCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            title = request.title
        )

        updateVisitTitleHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Update estimated completion date for an active visit
     * PATCH /api/visits/{visitId}/estimated-completion-date
     *
     * Allowed for visits in DRAFT, IN_PROGRESS, READY_FOR_PICKUP status.
     * Pass null to clear the date.
     */
    @PatchMapping("/{visitId}/estimated-completion-date")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun updateEstimatedCompletionDate(
        @PathVariable visitId: String,
        @RequestBody request: UpdateEstimatedCompletionDateRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateEstimatedCompletionDateCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            estimatedCompletionDate = request.estimatedCompletionDate
        )

        updateEstimatedCompletionDateHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Cancel a DRAFT visit
     * DELETE /api/visits/{visitId}/cancel
     *
     * Validates visit is in DRAFT status and cancels it.
     * Appointment remains in CONFIRMED status (ready to be converted again).
     */
    @DeleteMapping("/{visitId}/cancel")
    @RequiresPermission(Permission.VISITS_CHANGE_STATUS)
    fun cancelDraftVisit(
        @PathVariable visitId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = CancelDraftVisitCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        cancelDraftVisitHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Permanently delete a visit regardless of its status
     * DELETE /api/visits/{visitId}
     *
     * Deletes the visit and all associated data:
     * - Photos (DB records + S3 files)
     * - Protocols (DB records + S3 files)
     * - Damage map (S3 file)
     * - Documents (DB records + S3 files)
     * - Comments and notes (hard delete from DB)
     * - Visit entity itself (cascades to service items, journal entries)
     *
     * Appointment remains unchanged.
     * Only OWNER and MANAGER roles are allowed.
     */
    @DeleteMapping("/{visitId}")
    @RequiresPermission(Permission.VISITS_DELETE)
    fun deleteVisit(
        @PathVariable visitId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = DeleteVisitCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        deleteVisitHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Map domain result to API response
     */
    private fun mapToVisitDetailResponse(result: GetVisitDetailResult, showPrices: Boolean, doorToDoor: DoorToDoor? = null): VisitDetailResponse {
        return VisitDetailResponse(
            visit = mapToVisitResponse(result.visit, result.vehicle, result.customer, result.customerStats, result.appointmentColor, showPrices, doorToDoor),
            journalEntries = result.journalEntries.map { mapToJournalEntryResponse(it) },
            documents = result.documents.map { mapToDocumentResponse(it) }
        )
    }

    /**
     * Map Visit domain to VisitResponse
     */
    private fun mapToVisitResponse(
        visit: Visit,
        vehicle: Vehicle,
        customer: Customer,
        customerStats: CustomerStats,
        appointmentColor: pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity.AppointmentColorDomain?,
        showPrices: Boolean,
        doorToDoor: DoorToDoor? = null
    ): VisitResponse {
        return VisitResponse(
            id = visit.id.value.toString(),
            visitNumber = visit.visitNumber,
            title = visit.title,
            status = mapVisitStatus(visit.status),
            scheduledDate = visit.scheduledDate,
            estimatedCompletionDate = visit.estimatedCompletionDate,
            actualCompletionDate = visit.actualCompletionDate,
            pickupDate = visit.pickupDate,
            vehicle = mapToVehicleInfoResponse(vehicle),
            customer = mapToCustomerInfoResponse(customer, customerStats),
            appointmentColor = appointmentColor?.let { color ->
                AppointmentColorResponse(
                    id = color.id.value.toString(),
                    name = color.name,
                    hexColor = color.hexColor
                )
            },
            services = buildServiceLineItems(visit.serviceItems, showPrices),
            totalCost = if (showPrices) MoneyAmountResponse(
                netAmount = visit.calculateTotalNet().amountInCents,
                grossAmount = visit.calculateTotalGross().amountInCents,
                currency = "PLN"
            ) else null,
            mileageAtArrival = visit.mileageAtArrival,
            keysHandedOver = visit.keysHandedOver,
            documentsHandedOver = visit.documentsHandedOver,
            vehicleHandoff = visit.vehicleHandoff?.let { handoff ->
                VehicleHandoffResponse(
                    isHandedOffByOtherPerson = handoff.isHandedOffByOtherPerson,
                    contactPerson = handoff.contactPerson?.let { contact ->
                        ContactPersonResponse(
                            firstName = contact.firstName,
                            lastName = contact.lastName,
                            phone = contact.phone,
                            email = contact.email
                        )
                    }
                )
            },
            technicalNotes = visit.technicalNotes,
            smsReminderSuppressed = visit.smsReminderSuppressed,
            doorToDoor = doorToDoor?.let { d2d ->
                DoorToDoorInfoResponse(
                    id = d2d.id.toString(),
                    pickupAddress = DoorToDoorAddressInfo(d2d.pickupAddress.city, d2d.pickupAddress.street),
                    deliveryAddress = DoorToDoorAddressInfo(d2d.deliveryAddress.city, d2d.deliveryAddress.street),
                    notes = d2d.notes,
                    status = d2d.status.name
                )
            },
            createdAt = visit.createdAt,
            updatedAt = visit.updatedAt
        )
    }

    /**
     * Map Vehicle domain to VehicleInfoResponse
     */
    private fun mapToVehicleInfoResponse(vehicle: Vehicle): VehicleInfoResponse {
        return VehicleInfoResponse(
            id = vehicle.id.value.toString(),
            licensePlate = vehicle.licensePlate,
            brand = vehicle.brand,
            model = vehicle.model,
            yearOfProduction = vehicle.yearOfProduction,
            color = vehicle.color,
            currentMileage = vehicle.currentMileage
        )
    }

    /**
     * Map Customer domain to CustomerInfoResponse
     */
    private fun mapToCustomerInfoResponse(customer: Customer, stats: CustomerStats): CustomerInfoResponse {
        return CustomerInfoResponse(
            id = customer.id.value.toString(),
            firstName = customer.firstName,
            lastName = customer.lastName,
            email = customer.email,
            phone = customer.phone,
            companyName = customer.companyData?.name,
            stats = CustomerStatsResponse(
                totalVisits = stats.totalVisits,
                totalSpent = MoneyAmountResponse(
                    netAmount = stats.totalSpent.amountInCents,
                    grossAmount = stats.totalSpent.amountInCents, // For totalSpent, we show net = gross
                    currency = "PLN"
                ),
                vehiclesCount = stats.vehiclesCount
            )
        )
    }

    /**
     * Batch-load package info for all service items in a visit, then map each item.
     * Avoids N+1 queries: one query for service metadata, one for package items.
     */
    private fun buildServiceLineItems(serviceItems: List<VisitServiceItem>, showPrices: Boolean): List<ServiceLineItemResponse> {
        val serviceIds = serviceItems.mapNotNull { it.serviceId?.value }.distinct()
        if (serviceIds.isEmpty()) return serviceItems.map { mapToServiceLineItemResponse(it, isPackage = false, packageItems = null, showPrices = showPrices) }

        val packageServiceIds = serviceRepository.findAllById(serviceIds)
            .filter { it.isPackage }
            .map { it.id }
            .toSet()

        val packageItemsByServiceId: Map<UUID, List<PackageItemDto>> = if (packageServiceIds.isNotEmpty()) {
            packageItemRepository.findByPackageIdIn(packageServiceIds.toList())
                .groupBy({ it.packageId }, { PackageItemDto(it.serviceId.toString(), it.serviceName, it.position) })
        } else emptyMap()

        return serviceItems.map { item ->
            val sid = item.serviceId?.value
            val isPkg = sid != null && sid in packageServiceIds
            val items = if (isPkg && sid != null) packageItemsByServiceId[sid]?.sortedBy { it.position } else null
            mapToServiceLineItemResponse(item, isPkg, items, showPrices)
        }
    }

    /**
     * Map VisitServiceItem to ServiceLineItemResponse
     */
    private fun mapToServiceLineItemResponse(serviceItem: VisitServiceItem): ServiceLineItemResponse =
        mapToServiceLineItemResponse(serviceItem, isPackage = false, packageItems = null, showPrices = true)

    private fun mapToServiceLineItemResponse(
        serviceItem: VisitServiceItem,
        isPackage: Boolean,
        packageItems: List<PackageItemDto>?,
        showPrices: Boolean
    ): ServiceLineItemResponse {
        val adjustmentValue: Number = when (serviceItem.adjustmentType) {
            AdjustmentType.PERCENT -> serviceItem.adjustmentValue / 100.0
            else -> serviceItem.adjustmentValue
        }

        return ServiceLineItemResponse(
            id = serviceItem.id.value.toString(),
            serviceId = serviceItem.serviceId?.value?.toString(),
            serviceName = serviceItem.serviceName,
            basePriceNet = if (showPrices) serviceItem.basePriceNet.amountInCents else null,
            vatRate = if (showPrices) serviceItem.vatRate.rate else null,
            adjustment = if (showPrices) AdjustmentResponse(
                type = mapAdjustmentType(serviceItem.adjustmentType),
                value = adjustmentValue
            ) else null,
            note = serviceItem.customNote ?: "",
            status = mapServiceStatus(serviceItem.status),
            finalPriceNet = if (showPrices) serviceItem.finalPriceNet.amountInCents else null,
            finalPriceGross = if (showPrices) serviceItem.finalPriceGross.amountInCents else null,
            isPackage = isPackage,
            packageItems = packageItems,
            pendingOperation = serviceItem.pendingOperation?.let { mapPendingOperation(it) },
            hasPendingChange = serviceItem.pendingOperation != null,
            previousPriceNet = if (showPrices) serviceItem.confirmedSnapshot?.finalPriceNet?.amountInCents else null,
            previousPriceGross = if (showPrices) serviceItem.confirmedSnapshot?.finalPriceGross?.amountInCents else null
        )
    }

    /**
     * Map VisitJournalEntry to JournalEntryResponse
     */
    private fun mapToJournalEntryResponse(entry: VisitJournalEntry): JournalEntryResponse {
        return JournalEntryResponse(
            id = entry.id.value.toString(),
            type = mapJournalEntryType(entry.type),
            content = entry.content,
            createdBy = entry.createdByName,
            createdAt = entry.createdAt,
            isDeleted = entry.isDeleted
        )
    }

    /**
     * Map VisitDocument to VisitDocumentResponse
     */
    private fun mapToDocumentResponse(document: VisitDocument): VisitDocumentResponse {
        return VisitDocumentResponse(
            id = document.id.value.toString(),
            type = mapDocumentType(document.type),
            fileName = document.fileName,
            // The document itself carries personal data — its presigned URL is withheld
            // when the requester's context does not grant personal-data access.
            fileUrl = if (PiiAccessContext.isGranted()) document.fileUrl else null,
            uploadedAt = document.uploadedAt,
            uploadedBy = document.uploadedByName,
            category = document.category
        )
    }

    /**
     * Map VisitServiceStatus enum to frontend string
     */
    private fun mapServiceStatus(status: VisitServiceStatus): String {
        return when (status) {
            VisitServiceStatus.PENDING -> "PENDING"
            VisitServiceStatus.APPROVED -> "APPROVED"
            VisitServiceStatus.REJECTED -> "REJECTED"
            VisitServiceStatus.CONFIRMED -> "CONFIRMED"
        }
    }

    /**
     * Map PendingOperation enum to frontend string
     */
    private fun mapPendingOperation(operation: PendingOperation): String {
        return when (operation) {
            PendingOperation.ADD -> "ADD"
            PendingOperation.EDIT -> "EDIT"
            PendingOperation.DELETE -> "DELETE"
        }
    }

    /**
     * Map VisitStatus enum to frontend string
     */
    private fun mapVisitStatus(status: VisitStatus): String {
        return when (status) {
            VisitStatus.DRAFT -> "draft"
            VisitStatus.IN_PROGRESS -> "in_progress"
            VisitStatus.READY_FOR_PICKUP -> "ready_for_pickup"
            VisitStatus.COMPLETED -> "completed"
            VisitStatus.REJECTED -> "rejected"
            VisitStatus.ARCHIVED -> "archived"
        }
    }

    /**
     * Map AdjustmentType enum to frontend string
     */
    private fun mapAdjustmentType(adjustmentType: AdjustmentType): String {
        return when (adjustmentType) {
            AdjustmentType.PERCENT -> "PERCENT"
            AdjustmentType.FIXED_NET -> "FIXED_NET"
            AdjustmentType.FIXED_GROSS -> "FIXED_GROSS"
            AdjustmentType.SET_NET -> "SET_NET"
            AdjustmentType.SET_GROSS -> "SET_GROSS"
        }
    }

    /**
     * Map JournalEntryType enum to frontend string
     */
    private fun mapJournalEntryType(type: JournalEntryType): String {
        return when (type) {
            JournalEntryType.INTERNAL_NOTE -> "internal_note"
            JournalEntryType.CUSTOMER_COMMUNICATION -> "customer_communication"
        }
    }

    /**
     * Map DocumentType enum to frontend string
     */
    private fun mapDocumentType(type: DocumentType): String {
        return when (type) {
            DocumentType.PHOTO -> "photo"
            DocumentType.PDF -> "pdf"
            DocumentType.PROTOCOL -> "protocol"
            DocumentType.INTAKE -> "intake"
            DocumentType.DAMAGE_MAP -> "damage_map"
            DocumentType.OUTTAKE -> "outtake"
            DocumentType.OTHER -> "other"
        }
    }
}

/**
 * Response wrapper for visit list with pagination
 */
data class VisitListResponse(
    val visits: List<VisitListItem>,
    val pagination: PaginationMetadata
)

/**
 * Pagination metadata
 */
data class PaginationMetadata(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class ConfirmVisitRequest(
    val sendEmail: Boolean = false,
    val emailOptions: ConfirmEmailOptionsRequest? = null
)

data class ConfirmEmailOptionsRequest(
    val attachProtocol: Boolean = true,
    val attachPhotos: Boolean = false,
    val photoIds: List<String> = emptyList(),
    val attachDamageMap: Boolean = false
)

data class ConfirmVisitResponse(
    val visitId: String,
    val message: String
)

/**
 * Response for visit photos
 */
data class VisitPhotosResponse(
    val photos: List<VisitPhotoResponse>
)

/**
 * Individual photo response with presigned URLs
 */
data class VisitPhotoResponse(
    val id: String,
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant,
    val thumbnailUrl: String,
    val fullSizeUrl: String,
    val tags: List<String>
)

data class UpdateVisitTitleRequest(
    val title: String?
)

data class UpdateEstimatedCompletionDateRequest(
    val estimatedCompletionDate: Instant?
)

/**
 * Request to add a photo to a visit
 */
data class AddPhotoRequest(
    val fileName: String,
    val description: String?
)

/**
 * Response when adding a photo
 */
data class AddPhotoResponse(
    val photoId: String,
    val uploadUrl: String,
    val fileId: String
)
