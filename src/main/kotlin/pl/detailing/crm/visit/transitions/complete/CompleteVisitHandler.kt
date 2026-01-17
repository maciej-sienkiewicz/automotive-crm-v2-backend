package pl.detailing.crm.visit.transitions.complete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    suspend fun handle(command: CompleteVisitCommand): CompleteVisitResult = withContext(Dispatchers.IO) {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

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

        CompleteVisitResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status,
            completedAt = updatedVisit.completedDate!!
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
