package pl.detailing.crm.visit.transitions.complete

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class CompleteVisitHandler(
    private val visitRepository: VisitRepository
    // TODO: Add services for side-effects (InvoiceService, EmailService, etc.)
) {

    @Transactional
    suspend fun handle(command: CompleteVisitCommand): CompleteVisitResult {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        // Force load lazy collections within transaction
        visitEntity.serviceItems.size  // Force load serviceItems
        visitEntity.photos.size  // Force load photos

        val visit = visitEntity.toDomain()

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.complete(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Side-effects (to be implemented)
        // TODO: Generate invoice if needed
        // TODO: Send completion notification to customer
        // TODO: Update vehicle mileage if needed
        // Example:
        // invoiceService.generateInvoiceForVisit(updatedVisit)
        // notificationService.notifyCustomerVisitCompleted(
        //     customerId = visit.customerId,
        //     visitNumber = visit.visitNumber
        // )

        return CompleteVisitResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status,
            completedAt = updatedVisit.pickupDate!!
        )
    }
}

/**
 * Command to complete visit (hand over vehicle to customer)
 */
data class CompleteVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId
)

/**
 * Result of completing visit
 */
data class CompleteVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus,
    val completedAt: java.time.Instant
)
