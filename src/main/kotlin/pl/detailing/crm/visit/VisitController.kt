package pl.detailing.crm.visit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.get.*
import pl.detailing.crm.visit.list.*
import pl.detailing.crm.visit.domain.*
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.appointment.domain.AdjustmentType

@RestController
@RequestMapping("/api/visits")
class VisitController(
    private val listVisitsHandler: ListVisitsHandler,
    private val getVisitDetailHandler: GetVisitDetailHandler
) {

    /**
     * Get all visits for the studio with pagination and filtering
     * GET /api/visits?page=0&size=20&status=IN_PROGRESS
     */
    @GetMapping
    fun getVisits(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) status: String?
    ): ResponseEntity<VisitListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val visitStatus = status?.let {
            try {
                VisitStatus.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        val command = pl.detailing.crm.visit.list.ListVisitsCommand(
            studioId = principal.studioId,
            page = maxOf(0, page),
            pageSize = maxOf(1, minOf(100, size)), // Limit page size to 100
            status = visitStatus
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
     * Map domain result to API response
     */
    private fun mapToVisitDetailResponse(result: GetVisitDetailResult): VisitDetailResponse {
        return VisitDetailResponse(
            visit = mapToVisitResponse(result.visit, result.vehicle, result.customer, result.customerStats),
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
        customerStats: CustomerStats
    ): VisitResponse {
        val totalNet = visit.calculateTotalNet()
        val totalGross = visit.calculateTotalGross()

        return VisitResponse(
            id = visit.id.value.toString(),
            visitNumber = visit.visitNumber,
            status = mapVisitStatus(visit.status),
            scheduledDate = visit.scheduledDate.toString(),
            completedDate = visit.completedDate?.toString(),
            vehicle = mapToVehicleInfoResponse(vehicle),
            customer = mapToCustomerInfoResponse(customer, customerStats),
            services = visit.serviceItems.map { mapToServiceLineItemResponse(it) },
            totalCost = MoneyAmountResponse(
                netAmount = totalNet.amountInCents,
                grossAmount = totalGross.amountInCents,
                currency = "PLN"
            ),
            mileageAtArrival = visit.mileageAtArrival,
            keysHandedOver = visit.keysHandedOver,
            documentsHandedOver = visit.documentsHandedOver,
            technicalNotes = visit.technicalNotes,
            createdAt = visit.createdAt.toString(),
            updatedAt = visit.updatedAt.toString()
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
            engineType = mapEngineType(vehicle.engineType),
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
        return ServiceLineItemResponse(
            id = serviceItem.id.value.toString(),
            serviceId = serviceItem.serviceId.value.toString(),
            serviceName = serviceItem.serviceName,
            basePriceNet = serviceItem.basePriceNet.amountInCents,
            vatRate = serviceItem.vatRate.rate,
            adjustment = AdjustmentResponse(
                type = mapAdjustmentType(serviceItem.adjustmentType),
                value = serviceItem.adjustmentValue
            ),
            note = serviceItem.customNote ?: "",
            finalPriceNet = serviceItem.finalPriceNet.amountInCents,
            finalPriceGross = serviceItem.finalPriceGross.amountInCents
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
            createdAt = entry.createdAt.toString(),
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
            uploadedAt = document.uploadedAt.toString(),
            uploadedBy = document.uploadedByName,
            category = document.category
        )
    }

    /**
     * Map VisitStatus enum to frontend string
     */
    private fun mapVisitStatus(status: VisitStatus): String {
        return when (status) {
            VisitStatus.ACCEPTED -> "accepted"
            VisitStatus.IN_PROGRESS -> "in_progress"
            VisitStatus.READY -> "ready"
            VisitStatus.COMPLETED -> "completed"
            VisitStatus.CANCELLED -> "cancelled"
        }
    }

    /**
     * Map EngineType enum to frontend string
     */
    private fun mapEngineType(engineType: EngineType): String {
        return when (engineType) {
            EngineType.GASOLINE -> "gasoline"
            EngineType.DIESEL -> "diesel"
            EngineType.HYBRID -> "hybrid"
            EngineType.ELECTRIC -> "electric"
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
