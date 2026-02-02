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
    val scheduledDate: Instant,  // Start date (set when visit is created/scheduled)
    val estimatedCompletionDate: Instant?,  // Estimated completion date (set when visit is scheduled)
    val actualCompletionDate: Instant?,  // Actual completion date (set when status changes to READY_FOR_PICKUP)
    val pickupDate: Instant?,  // Pickup date (set when status changes to COMPLETED)

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

    // Damage map (S3 file ID for the generated damage map image)
    val damageMapFileId: String?,

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
            actualCompletionDate = Instant.now(),
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Update visit with damage map file ID
     */
    fun withDamageMap(damageMapFileId: String?, updatedBy: UserId): Visit {
        return copy(
            damageMapFileId = damageMapFileId,
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
            pickupDate = Instant.now(),
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

    /**
     * Save services changes - adds, updates and deletes services.
     * All changes result in service items being set to PENDING status,
     * requiring customer approval.
     */
    fun saveServicesChanges(
        added: List<VisitServiceItem>,
        updated: List<VisitServiceItem>,
        deletedIds: List<VisitServiceItemId>,
        updatedBy: UserId
    ): Visit {
        // Only allowed in certain states, though usually allowed while IN_PROGRESS
        // If we want to restrict this to only IN_PROGRESS:
        // if (status != VisitStatus.IN_PROGRESS) throw IllegalStateException("Can only change services when visit is IN_PROGRESS")

        val currentItems = serviceItems.toMutableList()

        // 1. Remove deleted
        currentItems.removeIf { item -> deletedIds.any { it == item.id } }

        // 2. Update existing
        updated.forEach { updatedItem ->
            val index = currentItems.indexOfFirst { it.id == updatedItem.id }
            if (index != -1) {
                currentItems[index] = updatedItem
            }
        }

        // 3. Add new
        currentItems.addAll(added)

        return copy(
            serviceItems = currentItems,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Approve a pending service item
     * Transitions the service from PENDING to CONFIRMED, or removes it if operation was DELETE
     */
    fun approveService(serviceItemId: VisitServiceItemId, updatedBy: UserId): Visit {
        val updatedItems = serviceItems.mapNotNull { item ->
            if (item.id == serviceItemId) {
                item.approve()  // Returns null for DELETE operations, confirmed item for ADD/EDIT
            } else {
                item
            }
        }

        return copy(
            serviceItems = updatedItems,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    /**
     * Reject a pending service item
     * Reverts the change or removes the item depending on the pending operation type
     */
    fun rejectService(serviceItemId: VisitServiceItemId, updatedBy: UserId): Visit {
        val updatedItems = serviceItems.mapNotNull { item ->
            if (item.id == serviceItemId) {
                item.reject()  // Returns null for ADD operations, restored item for EDIT/DELETE
            } else {
                item
            }
        }

        return copy(
            serviceItems = updatedItems,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }
}

/**
 * Snapshot of confirmed service state for rollback capability
 *
 * Stores the last confirmed state of a service item to enable
 * reverting pending changes (edit/delete operations).
 */
data class ConfirmedServiceSnapshot(
    val basePriceNet: Money,
    val vatRate: VatRate,
    val adjustmentType: AdjustmentType,
    val adjustmentValue: Long,
    val finalPriceNet: Money,
    val finalPriceGross: Money,
    val customNote: String?
)

/**
 * Individual service item within a visit with granular status tracking
 *
 * Service prices are frozen at the moment of adding to visit, ensuring
 * future price changes don't affect ongoing work.
 */
data class VisitServiceItem(
    val id: VisitServiceItemId,
    val serviceId: ServiceId?,
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

    // Pending operation tracking
    val pendingOperation: PendingOperation?,

    // Snapshot of confirmed state (for rollback)
    val confirmedSnapshot: ConfirmedServiceSnapshot?,

    // Optional note
    val customNote: String?,

    // Timestamps
    val createdAt: Instant,
    val confirmedAt: Instant?,
    val pendingAt: Instant?
) {
    companion object {
        fun createPending(
            serviceId: ServiceId?,
            serviceName: String,
            basePriceNet: Money,
            vatRate: VatRate,
            adjustmentType: AdjustmentType,
            adjustmentValue: Long,
            customNote: String?
        ): VisitServiceItem {
            val finalNet = PriceCalculator.calculateFinalNet(basePriceNet, vatRate, adjustmentType, adjustmentValue)
            val finalGross = vatRate.calculateGrossAmount(finalNet)

            return VisitServiceItem(
                id = VisitServiceItemId.random(),
                serviceId = serviceId,
                serviceName = serviceName,
                basePriceNet = basePriceNet,
                vatRate = vatRate,
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValue,
                finalPriceNet = finalNet,
                finalPriceGross = finalGross,
                status = VisitServiceStatus.PENDING,
                pendingOperation = PendingOperation.ADD,
                confirmedSnapshot = null,
                customNote = customNote,
                createdAt = Instant.now(),
                confirmedAt = null,
                pendingAt = Instant.now()
            )
        }
    }

    fun toPending(newBasePriceNet: Money): VisitServiceItem {
        require(status == VisitServiceStatus.CONFIRMED) { "Only confirmed items can be edited" }
        
        val snapshot = ConfirmedServiceSnapshot(
            basePriceNet = basePriceNet,
            vatRate = vatRate,
            adjustmentType = adjustmentType,
            adjustmentValue = adjustmentValue,
            finalPriceNet = finalPriceNet,
            finalPriceGross = finalPriceGross,
            customNote = customNote
        )
        
        val finalNet = PriceCalculator.calculateFinalNet(newBasePriceNet, vatRate, adjustmentType, adjustmentValue)
        val finalGross = vatRate.calculateGrossAmount(finalNet)

        return copy(
            basePriceNet = newBasePriceNet,
            finalPriceNet = finalNet,
            finalPriceGross = finalGross,
            status = VisitServiceStatus.PENDING,
            pendingOperation = PendingOperation.EDIT,
            confirmedSnapshot = snapshot,
            pendingAt = Instant.now()
        )
    }

    /**
     * Approve pending service item
     * Returns null if operation was DELETE (item should be removed)
     * Returns confirmed item for ADD and EDIT operations
     */
    fun approve(): VisitServiceItem? {
        require(status == VisitServiceStatus.PENDING) { "Only pending items can be approved" }
        
        return when (pendingOperation) {
            PendingOperation.DELETE -> null  // Remove the item
            PendingOperation.ADD, PendingOperation.EDIT -> {
                copy(
                    status = VisitServiceStatus.CONFIRMED,
                    pendingOperation = null,
                    confirmedSnapshot = null,
                    confirmedAt = Instant.now(),
                    pendingAt = null
                )
            }
            null -> this  // Should not happen, but handle gracefully
        }
    }

    /**
     * Reject pending service item
     * Returns null if operation was ADD (item should be removed)
     * Returns restored item if operation was EDIT or DELETE
     */
    fun reject(): VisitServiceItem? {
        require(status == VisitServiceStatus.PENDING) { "Only pending items can be rejected" }
        
        return when (pendingOperation) {
            PendingOperation.ADD -> null  // Remove the item
            PendingOperation.EDIT -> {
                // Restore from snapshot
                confirmedSnapshot?.let { snapshot ->
                    copy(
                        basePriceNet = snapshot.basePriceNet,
                        vatRate = snapshot.vatRate,
                        adjustmentType = snapshot.adjustmentType,
                        adjustmentValue = snapshot.adjustmentValue,
                        finalPriceNet = snapshot.finalPriceNet,
                        finalPriceGross = snapshot.finalPriceGross,
                        customNote = snapshot.customNote,
                        status = VisitServiceStatus.CONFIRMED,
                        pendingOperation = null,
                        confirmedSnapshot = null,
                        pendingAt = null
                    )
                }
            }
            PendingOperation.DELETE -> {
                // Restore the item
                copy(
                    status = VisitServiceStatus.CONFIRMED,
                    pendingOperation = null,
                    pendingAt = null
                )
            }
            null -> this
        }
    }

    /**
     * Mark confirmed service item for deletion
     * Sets status to PENDING with DELETE operation
     */
    fun markForDeletion(): VisitServiceItem {
        require(status == VisitServiceStatus.CONFIRMED) { "Only confirmed items can be marked for deletion" }
        
        return copy(
            status = VisitServiceStatus.PENDING,
            pendingOperation = PendingOperation.DELETE,
            pendingAt = Instant.now()
        )
    }
}

/**
 * Common price calculation logic for services
 */
object PriceCalculator {
    /**
     * Price calculation engine - applies adjustment based on type
     */
    fun calculateFinalNet(
        basePriceNet: Money,
        vatRate: VatRate,
        adjustmentType: AdjustmentType,
        adjustmentValue: Long
    ): Money {
        return when (adjustmentType) {
            AdjustmentType.PERCENT -> {
                // adjustmentValue is percentage (e.g., -10 for 10% discount, +20 for 20% markup)
                val multiplier = 1.0 + (adjustmentValue.toDouble() / 100.0)
                val calculatedNet = (basePriceNet.amountInCents * multiplier).toLong()
                Money(calculatedNet.coerceAtLeast(0))
            }
            AdjustmentType.FIXED_NET -> {
                // adjustmentValue is fixed amount in cents to add/subtract from net
                val calculatedNet = basePriceNet.amountInCents + adjustmentValue
                Money(calculatedNet.coerceAtLeast(0))
            }
            AdjustmentType.FIXED_GROSS -> {
                // adjustmentValue is fixed amount to add/subtract from gross, recalculate net
                val baseGross = vatRate.calculateGrossAmount(basePriceNet)
                val newGross = (baseGross.amountInCents + adjustmentValue).coerceAtLeast(0)
                // Calculate net from gross: net = gross / (1 + vatRate/100)
                val vatMultiplier = 1.0 + (vatRate.rate.toDouble() / 100.0)
                val calculatedNet = (newGross / vatMultiplier).toLong()
                Money(calculatedNet.coerceAtLeast(0))
            }
            AdjustmentType.SET_NET -> {
                // adjustmentValue is the final net price
                Money(adjustmentValue.coerceAtLeast(0))
            }
            AdjustmentType.SET_GROSS -> {
                // adjustmentValue is the final gross price, calculate net from it
                val vatMultiplier = 1.0 + (vatRate.rate.toDouble() / 100.0)
                val calculatedNet = (adjustmentValue / vatMultiplier).toLong()
                Money(calculatedNet.coerceAtLeast(0))
            }
        }
    }
}

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
    val customerId: CustomerId,
    val type: DocumentType,
    val name: String,
    val fileName: String,
    val fileId: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedBy: UserId,
    val uploadedByName: String,
    val category: String?
)
