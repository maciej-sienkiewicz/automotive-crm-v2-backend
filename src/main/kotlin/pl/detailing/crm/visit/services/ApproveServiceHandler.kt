package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.shared.*

/**
 * Handler for approving pending service changes
 */
@Service
class ApproveServiceHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(
        visitId: VisitId,
        serviceItemId: VisitServiceItemId,
        studioId: StudioId,
        userId: UserId,
        userName: String? = null
    ) {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        // Force load lazy collection
        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()

        val serviceItem = visit.serviceItems.find { it.id == serviceItemId }

        // Approve the service item
        val updatedVisit = visit.approveService(serviceItemId, userId)

        // Save the updated visit
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Audit logging
        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = userId,
            userDisplayName = userName ?: "",
            module = AuditModule.VISIT,
            entityId = visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = AuditAction.SERVICE_UPDATED,
            metadata = buildMap {
                put("serviceItemId", serviceItemId.value.toString())
                put("operation", "APPROVE")
                if (serviceItem != null) {
                    put("serviceName", serviceItem.serviceName)
                    serviceItem.pendingOperation?.let { put("pendingOperation", it.name) }
                }
            }
        ))
    }
}
