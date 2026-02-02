package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.shared.*

/**
 * Handler for approving pending service changes
 */
@Service
class ApproveServiceHandler(
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

        // Approve the service item
        val updatedVisit = visit.approveService(serviceItemId, userId)

        // Save the updated visit
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)
    }
}
