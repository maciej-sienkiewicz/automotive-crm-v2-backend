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
    val appointmentColorId: AppointmentColorId?,

    // Immutable vehicle snapshots - frozen at visit creation
    val brandSnapshot: String,
    val modelSnapshot: String,
    val licensePlateSnapshot: String?,
    val vinSnapshot: String?,
    val yearOfProductionSnapshot: Int?,
    val colorSnapshot: String?,

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
     * Calculate total net amount for CONFIRMED and APPROVED services only
     * PENDING and REJECTED services are excluded from billing
     */
    fun calculateTotalNet(): Money {
        return serviceItems
            .filter { it.status == VisitServiceStatus.CONFIRMED || it.status == VisitServiceStatus.APPROVED }
            .fold(Money.ZERO) { acc, item -> acc.plus(item.finalPriceNet) }
    }

    /**
     * Calculate total gross amount for CONFIRMED and APPROVED services only
     */
    fun calculateTotalGross(): Money {
        return serviceItems
            .filter { it.status == VisitServiceStatus.CONFIRMED || it.status == VisitServiceStatus.APPROVED }
            .fold(Money.ZERO) { acc, item -> acc.plus(item.finalPriceGross) }
    }

    /**
     * Calculate total VAT amount
     */
    fun calculateTotalVat(): Money {
        return calculateTotalGross().minus(calculateTotalNet())
    }

    /**
     * Check if visit can be marked as READY_FOR_PICKUP
     * No services can be in PENDING status (all must be CONFIRMED, APPROVED, or REJECTED)
     */
    fun canBeMarkedAsReady(): Boolean {
        return serviceItems.none { it.status == VisitServiceStatus.PENDING }
    }

    /**
     * Get all pending services awaiting customer approval
     */
    fun getPendingServices(): List<VisitServiceItem> {
        return serviceItems.filter { it.status == VisitServiceStatus.PENDING }
    }

    /**
     * Get all confirmed services ready to execute
     */
    fun getConfirmedServices(): List<VisitServiceItem> {
        return serviceItems.filter { it.status == VisitServiceStatus.CONFIRMED }
    }

    /**
     * Get all approved services
     */
    fun getApprovedServices(): List<VisitServiceItem> {
        return serviceItems.filter { it.status == VisitServiceStatus.APPROVED }
    }

    // ========== State Transitions ==========

    /**
     * Mark visit as ready for pickup
     * Requires: all services must be completed or rejected
     * Transition: IN_PROGRESS → READY_FOR_PICKUP
     */
    fun markAsReadyForPickup(updatedBy: UserId): Visit {
        VisitStateMachine.validateTransition(status, VisitStatus.READY_FOR_PICKUP)

        if (!canBeMarkedAsReady()) {
            throw IllegalStateException(
                "Cannot mark visit as ready. There are ${getPendingServices().size} services awaiting approval."
            )
        }

        return copy(
            status = VisitStatus.READY_FOR_PICKUP,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Complete visit - mark as handed over to customer
     * Transition: READY_FOR_PICKUP → COMPLETED
     */
    fun complete(updatedBy: UserId): Visit {
        VisitStateMachine.validateTransition(status, VisitStatus.COMPLETED)

        return copy(
            status = VisitStatus.COMPLETED,
            completedDate = Instant.now(),
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Reject visit with reason
     * Transition: IN_PROGRESS → REJECTED
     */
    fun reject(updatedBy: UserId): Visit {
        VisitStateMachine.validateTransition(status, VisitStatus.REJECTED)

        return copy(
            status = VisitStatus.REJECTED,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Archive visit
     * Transition: COMPLETED → ARCHIVED or REJECTED → ARCHIVED
     */
    fun archive(updatedBy: UserId): Visit {
        VisitStateMachine.validateTransition(status, VisitStatus.ARCHIVED)

        return copy(
            status = VisitStatus.ARCHIVED,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Return visit to in-progress state (e.g., more work needed)
     * Transition: READY_FOR_PICKUP → IN_PROGRESS
     */
    fun returnToInProgress(updatedBy: UserId): Visit {
        VisitStateMachine.validateTransition(status, VisitStatus.IN_PROGRESS)

        return copy(
            status = VisitStatus.IN_PROGRESS,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
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
