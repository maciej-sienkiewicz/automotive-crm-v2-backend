package pl.detailing.crm.visit.domain

import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Core Visit domain model with immutable snapshots
 *
 * The Visit acts as a standalone contract, preserving vehicle state and service prices
 * at the moment of visit creation. This ensures audit and financial integrity.
 */
data class Visit(
    val id: VisitId,
    val studioId: StudioId,
    val visitNumber: String,
    val customerId: CustomerId,
    val vehicleId: VehicleId,
    val appointmentId: AppointmentId,

    // Immutable vehicle snapshots - frozen at visit creation
    val brandSnapshot: String,
    val modelSnapshot: String,
    val licensePlateSnapshot: String,
    val vinSnapshot: String?,
    val yearOfProductionSnapshot: Int,
    val colorSnapshot: String?,
    val engineTypeSnapshot: EngineType,

    // Visit status and dates
    val status: VisitStatus,
    val scheduledDate: Instant,
    val completedDate: Instant?,

    // Arrival details
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean,
    val documentsHandedOver: Boolean,
    val inspectionNotes: String?,
    val technicalNotes: String?,

    // Service items
    val serviceItems: List<VisitServiceItem>,

    // Photos
    val photos: List<VisitPhoto>,

    // Audit fields
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Calculate total net amount for services in progress or completed
     * REJECTED and ARCHIVED services are excluded from billing
     */
    fun calculateTotalNet(): Money {
        return serviceItems
            .filter { it.status != VisitServiceStatus.REJECTED && it.status != VisitServiceStatus.ARCHIVED }
            .fold(Money.ZERO) { acc, item -> acc.plus(item.finalPriceNet) }
    }

    /**
     * Calculate total gross amount for services in progress or completed
     * REJECTED and ARCHIVED services are excluded from billing
     */
    fun calculateTotalGross(): Money {
        return serviceItems
            .filter { it.status != VisitServiceStatus.REJECTED && it.status != VisitServiceStatus.ARCHIVED }
            .fold(Money.ZERO) { acc, item -> acc.plus(item.finalPriceGross) }
    }

    /**
     * Calculate total VAT amount
     */
    fun calculateTotalVat(): Money {
        return calculateTotalGross().minus(calculateTotalNet())
    }

    /**
     * Check if visit can be marked as READY
     * All services must be finished (no IN_PROGRESS)
     */
    fun canBeMarkedAsReady(): Boolean {
        return serviceItems.all { it.status != VisitServiceStatus.IN_PROGRESS }
    }

    /**
     * Get all services currently being worked on
     */
    fun getInProgressServices(): List<VisitServiceItem> {
        return serviceItems.filter { it.status == VisitServiceStatus.IN_PROGRESS }
    }
}

/**
 * Individual service item within a visit with granular status tracking
 *
 * Service prices are frozen at the moment of adding to visit, ensuring
 * future price changes don't affect ongoing work.
 */
data class VisitServiceItem(
    val id: VisitServiceItemId,
    val serviceId: ServiceId,
    val serviceName: String,

    // Frozen pricing snapshot
    val basePriceNet: Money,
    val vatRate: VatRate,
    val adjustmentType: AdjustmentType,
    val adjustmentValue: Long,
    val finalPriceNet: Money,
    val finalPriceGross: Money,

    // Granular status tracking
    val status: VisitServiceStatus,

    // Optional note
    val customNote: String?,

    // Creation timestamp
    val createdAt: Instant
)

/**
 * Photo documentation for a visit
 */
data class VisitPhoto(
    val id: VisitPhotoId,
    val photoType: PhotoType,
    val fileId: String,
    val fileName: String,
    val description: String?,
    val uploadedAt: Instant
)

/**
 * Journal entry for tracking internal notes and customer communication
 */
data class VisitJournalEntry(
    val id: VisitJournalEntryId,
    val type: JournalEntryType,
    val content: String,
    val createdBy: UserId,
    val createdByName: String,
    val createdAt: Instant,
    val isDeleted: Boolean = false
)

/**
 * Document attached to a visit
 */
data class VisitDocument(
    val id: VisitDocumentId,
    val type: DocumentType,
    val fileName: String,
    val fileId: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedBy: UserId,
    val uploadedByName: String,
    val category: String?
)
