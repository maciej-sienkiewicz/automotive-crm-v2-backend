package pl.detailing.crm.visit.services

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.smscampaigns.consent.ServiceChangesSummary
import pl.detailing.crm.smscampaigns.consent.SmsConsentService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.get.MoneyAmountResponse
import java.util.UUID

@Service
class SaveVisitServicesHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService,
    private val customerRepository: CustomerRepository,
    private val smsConsentService: SmsConsentService,
    private val serviceRepository: pl.detailing.crm.service.infrastructure.ServiceRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(SaveVisitServicesHandler::class.java)
    }

    @Transactional
    suspend fun handle(visitId: VisitId, studioId: StudioId, userId: UserId, payload: ServicesChangesPayload, userName: String? = null): MoneyAmountResponse {
        val visitEntity = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit $visitId not found in studio $studioId")

        // Force load lazy collection
        visitEntity.serviceItems.size

        val visit = visitEntity.toDomain()

        val serviceIds = payload.added.mapNotNull { it.serviceId?.let { id -> ServiceId.fromString(id) } }
        val servicesFromDb = if (serviceIds.isNotEmpty()) {
            serviceRepository.findAllById(serviceIds.map { it.value })
                .associateBy { it.id }
        } else emptyMap()

        val addedItems = payload.added.map { added ->
            val adjustmentType = added.adjustment?.type ?: pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT
            val adjustmentValue = added.adjustment?.value ?: 0.0

            val adjustmentValueLong = when (adjustmentType) {
                pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT ->
                    pl.detailing.crm.appointment.domain.AdjustmentType.convertPercentValueToBasisPoints(adjustmentValue)
                else -> adjustmentValue.toLong()
            }

            val serviceId = added.serviceId?.let { ServiceId.fromString(it) }
            val vatRate = if (serviceId != null) {
                val dbService = servicesFromDb[serviceId.value]
                    ?: throw EntityNotFoundException("Usługa o ID '${serviceId.value}' nie została znaleziona")
                VatRate.fromInt(dbService.vatRate)
            } else {
                VatRate.fromInt(added.vatRate)
            }

            VisitServiceItem.createPending(
                serviceId = serviceId,
                serviceName = added.serviceName,
                basePriceNet = Money(added.basePriceNet),
                vatRate = vatRate,
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValueLong,
                customNote = added.note
            )
        }

        val updatedItems = payload.updated.map { updated ->
            val existingItem = visit.serviceItems.find { it.id.value.toString() == updated.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${updated.serviceLineItemId} not found in visit $visitId")

            val newAdjustmentType = updated.adjustment?.type
            val newAdjustmentValue = updated.adjustment?.let { adj ->
                when (adj.type) {
                    pl.detailing.crm.appointment.domain.AdjustmentType.PERCENT ->
                        pl.detailing.crm.appointment.domain.AdjustmentType.convertPercentValueToBasisPoints(adj.value)
                    else -> adj.value.toLong()
                }
            }

            existingItem.toPending(Money(updated.basePriceNet), newAdjustmentType, newAdjustmentValue)
        }

        val deletedItems = payload.deleted.map { deleted ->
            val existingItem = visit.serviceItems.find { it.id.value.toString() == deleted.serviceLineItemId }
                ?: throw EntityNotFoundException("Service item ${deleted.serviceLineItemId} not found in visit $visitId")
            
            existingItem.markForDeletion()
        }

        val visitWithPendingChanges = visit.saveServicesChanges(
            added = addedItems,
            updated = updatedItems + deletedItems,  // Deleted items are now updated items with DELETE operation
            deletedIds = emptyList(),  // No physical deletion
            updatedBy = userId
        )

        val updatedVisit = if (!payload.requireConfirmation) {
            var autoApproved = visitWithPendingChanges
            visitWithPendingChanges.getPendingServices().forEach { item ->
                autoApproved = autoApproved.approveService(item.id, userId)
            }
            autoApproved
        } else {
            visitWithPendingChanges
        }

        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        val changes = mutableListOf<FieldChange>()
        if (payload.added.isNotEmpty()) {
            changes.add(FieldChange("servicesAdded", null, payload.added.size.toString()))
        }
        if (payload.updated.isNotEmpty()) {
            changes.add(FieldChange("servicesUpdated", null, payload.updated.size.toString()))
        }
        if (payload.deleted.isNotEmpty()) {
            changes.add(FieldChange("servicesDeleted", null, payload.deleted.size.toString()))
        }

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = userId,
            userDisplayName = userName ?: "",
            module = AuditModule.VISIT,
            entityId = visitId.value.toString(),
            entityDisplayName = "Wizyta #${visitEntity.visitNumber}",
            action = AuditAction.SERVICES_UPDATED,
            changes = changes
        ))

        if (payload.notifyCustomer) {
            val changesSummary = buildChangesSummary(payload, visit)
            if (payload.requireConfirmation) {
                sendConsentSms(visitEntity.customerId, studioId, visitId, updatedVisit, changesSummary)
            } else {
                sendNotificationSms(visitEntity.customerId, studioId, visitId, updatedVisit, changesSummary)
            }
        }

        return MoneyAmountResponse(
            netAmount = updatedVisit.calculateTotalNet().amountInCents,
            grossAmount = updatedVisit.calculateTotalGross().amountInCents,
            currency = "PLN"
        )
    }

    /**
     * Collects human-readable service names for each change type so the consent SMS
     * can tell the customer exactly what was added, removed, or repriced.
     *
     * - Added names come directly from [ServicesChangesPayload.added].
     * - Removed / price-changed names are resolved from [visit] by service item ID,
     *   because the payload only carries IDs for those operations.
     */
    private fun buildChangesSummary(
        payload: ServicesChangesPayload,
        visit: pl.detailing.crm.visit.domain.Visit
    ): ServiceChangesSummary {
        val itemNamesById = visit.serviceItems.associate { it.id.value.toString() to it.serviceName }

        return ServiceChangesSummary(
            addedNames = payload.added.map { it.serviceName },
            removedNames = payload.deleted.mapNotNull { itemNamesById[it.serviceLineItemId] },
            priceChangedNames = payload.updated.mapNotNull { itemNamesById[it.serviceLineItemId] }
        )
    }

    private fun sendNotificationSms(
        customerId: java.util.UUID,
        studioId: StudioId,
        visitId: VisitId,
        updatedVisit: pl.detailing.crm.visit.domain.Visit,
        changesSummary: ServiceChangesSummary
    ) {
        val customer = customerRepository.findByIdAndStudioId(customerId, studioId.value)

        val phone = customer?.phone
        if (phone.isNullOrBlank()) {
            logger.warn("notifyCustomer=true but customer {} has no phone – SMS skipped", customerId)
            return
        }

        val totalGross = updatedVisit.serviceItems
            .filter { it.pendingOperation != PendingOperation.DELETE }
            .sumOf { it.finalPriceGross.amountInCents }

        smsConsentService.sendServiceChangeNotification(
            visitId = visitId,
            studioId = studioId,
            customerPhone = phone,
            totalGrossCents = totalGross,
            changes = changesSummary
        )
    }

    /**
     * Calculates the proposed total gross price (if all pending changes were approved)
     * and dispatches a consent SMS to the customer.
     *
     * "Proposed total" includes all items except those pending deletion.
     */
    private fun sendConsentSms(
        customerId: java.util.UUID,
        studioId: StudioId,
        visitId: VisitId,
        updatedVisit: pl.detailing.crm.visit.domain.Visit,
        changesSummary: ServiceChangesSummary
    ) {
        val customer = customerRepository.findByIdAndStudioId(customerId, studioId.value)

        val phone = customer?.phone
        if (phone.isNullOrBlank()) {
            logger.warn("notifyCustomer=true but customer {} has no phone – SMS skipped", customerId)
            return
        }

        val proposedTotalGross = updatedVisit.serviceItems
            .filter { it.pendingOperation != PendingOperation.DELETE }
            .sumOf { it.finalPriceGross.amountInCents }

        smsConsentService.sendConsentRequest(
            visitId = visitId,
            studioId = studioId,
            customerPhone = phone,
            proposedTotalGrossCents = proposedTotalGross,
            changes = changesSummary
        )
    }
}
