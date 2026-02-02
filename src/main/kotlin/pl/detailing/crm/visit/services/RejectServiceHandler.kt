package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.shared.*

/**
 * Handler for rejecting pending service changes
 */
@Service
class RejectServiceHandler(
    private val visitRepository: VisitRepository
) {

    @Transactional
    suspend fun handle(
        visitId: VisitId,
        serviceItemId: VisitServiceItemId,
        studioId: StudioId,
        userId: UserId
    ) {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        // Force load lazy collection
        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()

        // Reject the service item
        val updatedVisit = visit.rejectService(serviceItemId, userId)

        // Save the updated visit
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)
    }
}
