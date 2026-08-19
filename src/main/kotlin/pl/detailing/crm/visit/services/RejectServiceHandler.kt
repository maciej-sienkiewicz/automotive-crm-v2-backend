package pl.detailing.crm.visit.services

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.domain.VisitAuditLabel
import pl.detailing.crm.visit.infrastructure.auditDisplayName
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.get.MoneyAmountResponse
import java.math.BigDecimal

/**
 * Handler for rejecting pending service changes
 */
@Service
class RejectServiceHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService,
    private val customerRepository: CustomerRepository
) {

    @Transactional
    suspend fun handle(
        visitId: VisitId,
        serviceItemId: VisitServiceItemId,
        studioId: StudioId,
        userId: UserId,
        userName: String? = null
    ): MoneyAmountResponse {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        // Force load lazy collection
        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()

        val serviceItem = visit.serviceItems.find { it.id == serviceItemId }

        // Reject the service item
        val updatedVisit = visit.rejectService(serviceItemId, userId)

        // Save the updated visit
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        val grossBefore = visit.calculateTotalGross().amountInCents
        val grossAfter = updatedVisit.calculateTotalGross().amountInCents
        val changes = mutableListOf<FieldChange>()
        if (grossBefore != grossAfter) {
            changes.add(FieldChange(
                "totalGross",
                BigDecimal(grossBefore).movePointLeft(2).toPlainString(),
                BigDecimal(grossAfter).movePointLeft(2).toPlainString(),
                AuditValueType.MONEY
            ))
        }
        serviceItem?.let { changes.add(FieldChange("status", "PENDING", "REJECTED")) }

        val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, studioId.value)
        val vehicleName = VisitAuditLabel.vehicleLabel(
            visitEntity.brandSnapshot,
            visitEntity.modelSnapshot,
            visitEntity.licensePlateSnapshot
        )
        val visitDisplayName = visitEntity.auditDisplayName

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = userId,
            userDisplayName = userName ?: "",
            module = AuditModule.VISIT,
            entityId = visitId.value.toString(),
            entityDisplayName = visitDisplayName,
            action = AuditAction.SERVICE_UPDATED,
            changes = changes,
            amount = AuditAmount(BigDecimal(grossAfter).movePointLeft(2)),
            context = AuditContext(
                customerId = CustomerId(visitEntity.customerId),
                customerName = customer?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").takeIf { n -> n.isNotBlank() } },
                vehicleId = VehicleId(visitEntity.vehicleId),
                vehicleName = vehicleName,
                visitId = visitId,
                visitName = visitDisplayName
            ),
            metadata = buildMap {
                put("serviceItemId", serviceItemId.value.toString())
                put("operation", "REJECT")
                serviceItem?.serviceName?.let { put("serviceName", it) }
            }
        ))

        return MoneyAmountResponse(
            netAmount = updatedVisit.calculateTotalNet().amountInCents,
            grossAmount = updatedVisit.calculateTotalGross().amountInCents,
            currency = "PLN"
        )
    }
}
