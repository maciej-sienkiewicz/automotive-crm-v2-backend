package pl.detailing.crm.leads.estimation.analyze

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.estimation.domain.CatalogService
import pl.detailing.crm.leads.estimation.domain.LeadAnalyzer
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationEntity
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationItemEntity
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationStatusJpa
import pl.detailing.crm.leads.estimation.infrastructure.RelatedVisit
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

@Service
class AnalyzeLeadHandler(
    private val leadRepository: LeadRepository,
    private val serviceRepository: ServiceRepository,
    private val leadEstimationRepository: LeadEstimationRepository,
    private val visitRepository: VisitRepository,
    private val leadAnalyzer: LeadAnalyzer,
    private val auditService: AuditService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: AnalyzeLeadCommand) {
        val leadEntity = withContext(Dispatchers.IO) {
            leadRepository.findById(command.leadId.value).orElse(null)
        } ?: run {
            log.warn("[LEAD_ANALYSIS] Lead {} not found, skipping", command.leadId)
            return
        }

        if (leadEntity.studioId != command.studioId.value) {
            log.warn("[LEAD_ANALYSIS] Lead {} does not belong to studio {}", command.leadId, command.studioId)
            return
        }

        // Remove previous estimation if exists (re-analysis scenario)
        withContext(Dispatchers.IO) {
            leadEstimationRepository.findByLeadId(command.leadId.value)
                ?.let { leadEstimationRepository.delete(it) }
        }

        val estimationId = UUID.randomUUID()
        val now = Instant.now()

        withContext(Dispatchers.IO) {
            leadEstimationRepository.save(
                LeadEstimationEntity(
                    id = estimationId,
                    leadId = command.leadId.value,
                    studioId = command.studioId.value,
                    status = LeadEstimationStatusJpa.PENDING,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        log.debug("[LEAD_ANALYSIS] Started for leadId={}", command.leadId)

        val catalogServices = withContext(Dispatchers.IO) {
            serviceRepository.findActiveByStudioId(command.studioId.value)
        }.map { s ->
            CatalogService(
                id = s.id.toString(),
                name = s.name,
                priceNet = s.basePriceNet,
                vatRate = s.vatRate,
                requireManualPrice = s.requireManualPrice
            )
        }

        if (catalogServices.isEmpty()) {
            withContext(Dispatchers.IO) { markCompleted(estimationId, emptyList(), emptyList(), emptyList(), 0L, emptyList<RelatedVisit>()) }
            log.info("[LEAD_ANALYSIS] No active services for studio {}, estimation empty", command.studioId)
            return
        }

        val analysisResult = try {
            leadAnalyzer.analyze(
                leadMessage = leadEntity.initialMessage ?: "",
                preExtractedNeeds = command.preExtractedNeeds,
                catalogServices = catalogServices,
                preExtractedVehicleMake = command.preExtractedVehicleMake,
                preExtractedVehicleModel = command.preExtractedVehicleModel
            )
        } catch (e: Exception) {
            log.error("[LEAD_ANALYSIS] LLM failed for leadId={}: {}", command.leadId, e.message)
            withContext(Dispatchers.IO) {
                leadEstimationRepository.findById(estimationId).ifPresent { est ->
                    est.status = LeadEstimationStatusJpa.FAILED
                    est.updatedAt = Instant.now()
                    leadEstimationRepository.save(est)
                }
            }
            publishLeadChanged(command.studioId, command.leadId.value)
            return
        }

        val catalogById = catalogServices.associateBy { it.id }

        withContext(Dispatchers.IO) {
            leadEstimationRepository.findById(estimationId).ifPresent { est ->
                val matchedServices = analysisResult.matchedServiceIds.mapNotNull { id -> catalogById[id] }
                val items = matchedServices.map { service ->
                    // Services flagged requireManualPrice have no valid catalog price —
                    // basePriceNet is a placeholder, so the estimate must not quote it.
                    LeadEstimationItemEntity(
                        id = UUID.randomUUID(),
                        estimation = est,
                        serviceId = UUID.fromString(service.id),
                        serviceName = service.name,
                        priceNet = if (service.requireManualPrice) 0 else service.priceNet,
                        vatRate = service.vatRate,
                        priceGross = if (service.requireManualPrice) 0 else grossFromNet(service.priceNet, service.vatRate),
                        manualPriceRequired = service.requireManualPrice
                    )
                }

                // Lookup historical visits for same brand+model with at least one matched service
                val relatedVisits = resolveRelatedVisits(
                    studioId = command.studioId.value,
                    vehicleBrand = analysisResult.vehicleBrand,
                    vehicleModel = analysisResult.vehicleModel,
                    matchedServiceIds = analysisResult.matchedServiceIds
                )

                est.extractedNeeds = analysisResult.extractedNeeds
                est.unmatchedNeeds = analysisResult.unmatchedNeeds
                est.items.addAll(items)
                est.totalGross = items.sumOf { it.priceGross }
                est.relatedVisits = relatedVisits
                est.aiSummary = command.overrideSummary ?: analysisResult.summary
                est.status = LeadEstimationStatusJpa.COMPLETED
                est.updatedAt = Instant.now()
                leadEstimationRepository.save(est)

                // Persist normalised vehicle on lead (only if LLM identified it and not already set)
                var leadDirty = false
                if (analysisResult.vehicleBrand != null && (leadEntity.vehicleBrand == null || leadEntity.vehicleModel == null)) {
                    leadEntity.vehicleBrand = analysisResult.vehicleBrand
                    leadEntity.vehicleModel = analysisResult.vehicleModel
                    leadEntity.updatedAt = Instant.now()
                    leadDirty = true
                }

                // Pre-fill lead.estimatedValue with AI total if admin hasn't set a price yet
                if (leadEntity.estimatedValue == 0L && est.totalGross > 0) {
                    leadEntity.estimatedValue = est.totalGross
                    leadEntity.updatedAt = Instant.now()
                    leadDirty = true
                }

                if (leadDirty) {
                    leadRepository.save(leadEntity)
                }

                // Audit log entry with estimated value after LLM processing completes
                val totalGross = est.totalGross
                auditService.logSync(LogAuditCommand(
                    studioId = command.studioId,
                    userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000000")),
                    userDisplayName = "System",
                    module = AuditModule.LEAD,
                    entityId = command.leadId.value.toString(),
                    entityDisplayName = leadEntity.customerName ?: leadEntity.contactIdentifier,
                    action = AuditAction.LEAD_ESTIMATION_COMPLETED,
                    changes = listOf(
                        FieldChange("estimatedValue", null, totalGross.toString())
                    )
                ))
            }
        }

        // Estimation runs in the background — without this event the new estimationStatus,
        // estimatedValue and vehicle data show up only after a manual page refresh.
        publishLeadChanged(command.studioId, command.leadId.value)

        log.info(
            "[LEAD_ANALYSIS] Completed for leadId={}: matched={} unmatched={} vehicle='{} {}' relatedVisits={}",
            command.leadId, analysisResult.matchedServiceIds.size, analysisResult.unmatchedNeeds.size,
            analysisResult.vehicleBrand, analysisResult.vehicleModel,
            "(see estimation)"
        )
    }

    private fun publishLeadChanged(studioId: StudioId, leadId: UUID) {
        eventPublisher.publishEvent(
            LeadChangedEvent(source = this, studioId = studioId, leadId = LeadId(leadId))
        )
    }

    private fun resolveRelatedVisits(
        studioId: UUID,
        vehicleBrand: String?,
        vehicleModel: String?,
        matchedServiceIds: List<String>
    ): List<RelatedVisit> {
        if (vehicleBrand == null || vehicleModel == null || matchedServiceIds.isEmpty()) return emptyList()
        return try {
            visitRepository.findByBrandModelAndServiceIds(
                studioId = studioId,
                brand = vehicleBrand,
                model = vehicleModel,
                serviceIds = matchedServiceIds.map { UUID.fromString(it) }
            ).map { RelatedVisit(id = it.id.toString(), title = it.title) }
        } catch (e: Exception) {
            log.warn("[LEAD_ANALYSIS] Failed to resolve related visits: {}", e.message)
            emptyList()
        }
    }

    private fun markCompleted(
        estimationId: UUID,
        extractedNeeds: List<String>,
        unmatchedNeeds: List<String>,
        items: List<LeadEstimationItemEntity>,
        totalGross: Long,
        relatedVisits: List<RelatedVisit>
    ) {
        leadEstimationRepository.findById(estimationId).ifPresent { est ->
            est.extractedNeeds = extractedNeeds
            est.unmatchedNeeds = unmatchedNeeds
            est.items.addAll(items)
            est.totalGross = totalGross
            est.relatedVisits = relatedVisits
            est.status = LeadEstimationStatusJpa.COMPLETED
            est.updatedAt = Instant.now()
            leadEstimationRepository.save(est)
        }
    }

    // VAT_ZW (rate = -1) means exempt — gross equals net
    private fun grossFromNet(netCents: Long, vatRate: Int): Long =
        if (vatRate < 0) netCents else netCents + (netCents * vatRate / 100)
}
