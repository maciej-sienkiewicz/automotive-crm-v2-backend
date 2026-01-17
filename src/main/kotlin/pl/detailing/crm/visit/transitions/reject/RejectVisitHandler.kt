package pl.detailing.crm.visit.transitions.reject

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class RejectVisitHandler(
    private val visitRepository: VisitRepository
    // TODO: Add services for side-effects (EmailService, SMSService, etc.)
) {

    @Transactional
    suspend fun handle(command: RejectVisitCommand): RejectVisitResult = withContext(Dispatchers.IO) {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        val visit = visitEntity.toDomain()

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.reject(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Store rejection reason in technical notes if provided
        if (command.rejectionReason != null) {
            val technicalNotes = if (updatedVisit.technicalNotes.isNullOrBlank()) {
                "REJECTED: ${command.rejectionReason}"
            } else {
                "${updatedVisit.technicalNotes}\n\nREJECTED: ${command.rejectionReason}"
            }
            updatedEntity.technicalNotes = technicalNotes
            visitRepository.save(updatedEntity)
        }

        // Step 5: Side-effects (to be implemented)
        // TODO: Send notification to customer about rejection
        // TODO: Cancel any pending operations
        // Example:
        // notificationService.notifyCustomerVisitRejected(
        //     customerId = visit.customerId,
        //     visitNumber = visit.visitNumber,
        //     reason = command.rejectionReason
        // )

        RejectVisitResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status
        )
    }
}

/**
 * Command to reject visit
 */
data class RejectVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val rejectionReason: String?
)

/**
 * Result of rejecting visit
 */
data class RejectVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus
)
