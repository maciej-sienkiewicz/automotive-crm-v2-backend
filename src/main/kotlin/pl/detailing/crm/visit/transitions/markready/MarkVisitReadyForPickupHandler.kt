package pl.detailing.crm.visit.transitions.markready

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class MarkVisitReadyForPickupHandler(
    private val visitRepository: VisitRepository
    // TODO: Add services for side-effects (EmailService, SMSService, etc.)
) {

    @Transactional
    suspend fun handle(command: MarkVisitReadyForPickupCommand): MarkVisitReadyForPickupResult = withContext(Dispatchers.IO) {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        val visit = visitEntity.toDomain()

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.markAsReadyForPickup(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Side-effects (to be implemented)
        // TODO: Send notification to customer (SMS/Email)
        // Example:
        // notificationService.notifyCustomerVisitReady(
        //     customerId = visit.customerId,
        //     visitNumber = visit.visitNumber,
        //     customerPhone = ...,
        //     customerEmail = ...
        // )

        MarkVisitReadyForPickupResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status
        )
    }
}

/**
 * Command to mark visit as ready for pickup
 */
data class MarkVisitReadyForPickupCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId
)

/**
 * Result of marking visit as ready for pickup
 */
data class MarkVisitReadyForPickupResult(
    val visitId: VisitId,
    val newStatus: VisitStatus
)
