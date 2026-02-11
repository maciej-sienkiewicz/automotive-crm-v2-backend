package pl.detailing.crm.visit

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.get.*
import pl.detailing.crm.visit.list.*
import pl.detailing.crm.visit.domain.*
import pl.detailing.crm.visit.transitions.confirm.ConfirmVisitCommand
import pl.detailing.crm.visit.transitions.confirm.ConfirmVisitHandler
import pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitCommand
import pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitHandler
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.visit.services.SaveVisitServicesHandler
import pl.detailing.crm.visit.services.ServicesChangesPayload
import java.time.LocalDate

@RestController
@RequestMapping("/api/visits")
class VisitController(
    private val listVisitsHandler: ListVisitsHandler,
    private val getVisitDetailHandler: GetVisitDetailHandler,
    private val saveVisitServicesHandler: SaveVisitServicesHandler,
    private val confirmVisitHandler: ConfirmVisitHandler,
    private val cancelDraftVisitHandler: CancelDraftVisitHandler
) {

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
        @RequestParam(required = false) scheduledDate: String?
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
            pageSize = maxOf(1, minOf(100, size)), // Limit page size to 100
            status = visitStatus,
            searchTerm = search,
            scheduledDate = scheduledDateFilter
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

        val response = mapToVisitDetailResponse(result)

        ResponseEntity.ok(response)
    }

    /**
     * Update visit services (add, update, delete)
     * PATCH /api/visits/{visitId}/services/
     */
    @PatchMapping("/{visitId}/services/")
    fun saveServicesChanges(
        @PathVariable visitId: String,
        @RequestBody payload: ServicesChangesPayload
    ): ResponseEntity<Unit> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        saveVisitServicesHandler.handle(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId,
            payload = payload
        )

        ResponseEntity.noContent().build()
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
    fun confirmVisit(
        @PathVariable visitId: String
    ): ResponseEntity<ConfirmVisitResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can confirm visits
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can confirm visits")
        }

        val command = ConfirmVisitCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId
        )

        val result = confirmVisitHandler.handle(command)

        ResponseEntity.ok(ConfirmVisitResponse(
            visitId = result.visitId.value.toString(),
            message = "Visit confirmed successfully"
        ))
    }

    /**
     * Cancel a DRAFT visit
     * DELETE /api/visits/{visitId}
     *
     * This endpoint:
     * - Validates visit is in DRAFT status (only DRAFT visits can be cancelled)
     * - Deletes all associated protocols from database and S3
     * - Deletes damage map from S3 if exists
     * - Deletes all documents associated with the visit
     * - Deletes the visit from database
     * - Appointment remains in CONFIRMED status (ready to be converted again)
     *
     * Note: Only DRAFT visits can be cancelled. Confirmed visits should use rejection flow.
     */
    @DeleteMapping("/{visitId}")
    fun cancelDraftVisit(
        @PathVariable visitId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        // Only OWNER and MANAGER can cancel visits
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can cancel visits")
        }

        val command = CancelDraftVisitCommand(
            visitId = VisitId.fromString(visitId),
            studioId = principal.studioId,
            userId = principal.userId
        )

        cancelDraftVisitHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Map domain result to API response
     */
    private fun mapToVisitDetailResponse(result: GetVisitDetailResult): VisitDetailResponse {
        return VisitDetailResponse(
            visit = mapToVisitResponse(result.visit, result.vehicle, result.customer, result.customerStats, result.appointmentColor),
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
        appointmentColor: pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity.AppointmentColorDomain?
    ): VisitResponse {
        val totalNet = visit.calculateTotalNet()
        val totalGross = visit.calculateTotalGross()

        return VisitResponse(
            id = visit.id.value.toString(),
            visitNumber = visit.visitNumber,
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
            services = visit.serviceItems.map { mapToServiceLineItemResponse(it) },
            totalCost = MoneyAmountResponse(
                netAmount = totalNet.amountInCents,
                grossAmount = totalGross.amountInCents,
                currency = "PLN"
            ),
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
     * Map VisitServiceItem to ServiceLineItemResponse
     */
    private fun mapToServiceLineItemResponse(serviceItem: VisitServiceItem): ServiceLineItemResponse {
        // Convert adjustment value based on type:
        // - For PERCENT: convert from basis points back to percentage (divide by 100)
        // - For others: keep as-is (in cents)
        val adjustmentValue = when (serviceItem.adjustmentType) {
            AdjustmentType.PERCENT -> serviceItem.adjustmentValue / 100.0 // Convert from basis points to percentage
            else -> serviceItem.adjustmentValue.toDouble() // Keep in cents
        }

        return ServiceLineItemResponse(
            id = serviceItem.id.value.toString(),
            serviceId = serviceItem.serviceId?.value?.toString(),
            serviceName = serviceItem.serviceName,
            basePriceNet = serviceItem.basePriceNet.amountInCents,
            vatRate = serviceItem.vatRate.rate,
            adjustment = AdjustmentResponse(
                type = mapAdjustmentType(serviceItem.adjustmentType),
                value = adjustmentValue
            ),
            note = serviceItem.customNote ?: "",
            status = mapServiceStatus(serviceItem.status),
            finalPriceNet = serviceItem.finalPriceNet.amountInCents,
            finalPriceGross = serviceItem.finalPriceGross.amountInCents,
            
            // Pending operation tracking
            pendingOperation = serviceItem.pendingOperation?.let { mapPendingOperation(it) },
            hasPendingChange = serviceItem.pendingOperation != null,
            
            // Previous values for EDIT operations
            previousPriceNet = serviceItem.confirmedSnapshot?.finalPriceNet?.amountInCents,
            previousPriceGross = serviceItem.confirmedSnapshot?.finalPriceGross?.amountInCents
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
            fileUrl = document.fileUrl,
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

/**
 * Response for visit confirmation
 */
data class ConfirmVisitResponse(
    val visitId: String,
    val message: String
)
