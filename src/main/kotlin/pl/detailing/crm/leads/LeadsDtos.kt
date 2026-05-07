package pl.detailing.crm.leads

import java.time.Instant
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.get.EstimationItemResult
import pl.detailing.crm.leads.get.EstimationResult
import pl.detailing.crm.leads.get.GetLeadResult
import pl.detailing.crm.leads.list.LeadListItem
import pl.detailing.crm.leads.estimation.infrastructure.RelatedVisit
import pl.detailing.crm.shared.LeadSource

data class RelatedVisitDto(
    val id: String,
    val title: String?
)

data class LeadDto(
    val id: String,
    val source: String?,
    val status: String,
    val contactIdentifier: String?,
    val customerName: String?,
    val initialMessage: String?,
    val aiReasoning: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val relatedVisits: List<RelatedVisitDto>
)

fun Lead.toDto(
    relatedVisits: List<RelatedVisitDto> = emptyList(),
    aiReasoning: String? = null
): LeadDto = LeadDto(
    id = this.id.toString(),
    source = this.source.name,
    status = this.status.name,
    contactIdentifier = this.contactIdentifier,
    customerName = this.customerName,
    initialMessage = this.initialMessage,
    aiReasoning = aiReasoning,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
    estimatedValue = this.estimatedValue,
    requiresVerification = this.requiresVerification,
    vehicleBrand = this.vehicleBrand,
    vehicleModel = this.vehicleModel,
    relatedVisits = relatedVisits
)

fun LeadListItem.toDto(): LeadDto = lead.toDto(
    relatedVisits = relatedVisits.map { RelatedVisitDto(id = it.id, title = it.title) },
    aiReasoning = aiReasoning
)

data class CreateLeadRequest(
    val source: LeadSource,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long
)

data class UpdateLeadRequest(
    val status: String?,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long?
)

data class UpdateStatusRequest(
    val status: String
)

data class UpdateValueRequest(
    val estimatedValue: Long
)

data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)

data class LeadListResponse(
    val leads: List<LeadDto>,
    val pagination: PaginationInfo
)

data class LeadDetailDto(
    val id: String,
    val source: String,
    val status: String,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val estimation: LeadEstimationDto?
)

data class LeadEstimationDto(
    val id: String,
    val status: String,
    val extractedNeeds: List<String>,
    val matchedItems: List<LeadEstimationItemDto>,
    val unmatchedNeeds: List<String>,
    val totalGross: Long,
    val relatedVisits: List<RelatedVisitDto>,
    val aiReasoning: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class LeadEstimationItemDto(
    val serviceId: String?,
    val serviceName: String,
    val priceNet: Long,
    val vatRate: Int,
    val priceGross: Long
)

fun GetLeadResult.toDetailDto() = LeadDetailDto(
    id = leadId.toString(),
    source = source.name,
    status = status.name,
    contactIdentifier = contactIdentifier,
    customerName = customerName,
    initialMessage = initialMessage,
    estimatedValue = estimatedValue,
    requiresVerification = requiresVerification,
    vehicleBrand = vehicleBrand,
    vehicleModel = vehicleModel,
    createdAt = createdAt,
    updatedAt = updatedAt,
    estimation = estimation?.toDto()
)

fun EstimationResult.toDto() = LeadEstimationDto(
    id = id.toString(),
    status = status,
    extractedNeeds = extractedNeeds,
    matchedItems = matchedItems.map { it.toDto() },
    unmatchedNeeds = unmatchedNeeds,
    totalGross = totalGross,
    relatedVisits = relatedVisits.map { RelatedVisitDto(id = it.id, title = it.title) },
    aiReasoning = aiReasoning,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun EstimationItemResult.toDto() = LeadEstimationItemDto(
    serviceId = serviceId?.toString(),
    serviceName = serviceName,
    priceNet = priceNet,
    vatRate = vatRate,
    priceGross = priceGross
)

data class PipelineSummaryDto(
    val totalPipelineValue: Long,
    val inProgressCount: Int,
    val convertedCount: Int,
    val abandonedCount: Int,
    val convertedThisWeekCount: Int,
    val convertedThisWeekValue: Long,
    val convertedPreviousWeekCount: Int,
    val convertedPreviousWeekValue: Long,
    val leadsValueThisMonth: Long,
    val convertedValueThisMonth: Long
)
