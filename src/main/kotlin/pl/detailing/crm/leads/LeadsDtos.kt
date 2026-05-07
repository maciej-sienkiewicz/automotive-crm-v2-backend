package pl.detailing.crm.leads

import java.time.Instant
import java.util.UUID
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.get.EstimationItemResult
import pl.detailing.crm.leads.get.EstimationResult
import pl.detailing.crm.leads.get.GetLeadResult
import pl.detailing.crm.shared.LeadSource

/**
 * DTO for Lead entity
 */
data class LeadDto(
    val id: String,
    val source: String?,
    val status: String,
    val contactIdentifier: String?,
    val customerName: String?,
    val initialMessage: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val estimatedValue: Long,
    val requiresVerification: Boolean
)

/**
 * Extension function to convert Lead domain to DTO
 */
fun Lead.toDto(): LeadDto = LeadDto(
    id = this.id.toString(),
    source = this.source.name,
    status = this.status.name,
    contactIdentifier = this.contactIdentifier,
    customerName = this.customerName,
    initialMessage = this.initialMessage,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
    estimatedValue = this.estimatedValue,
    requiresVerification = this.requiresVerification
)

/**
 * Request payload for creating a new lead
 */
data class CreateLeadRequest(
    val source: LeadSource,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long
)

/**
 * Request payload for updating a lead
 */
data class UpdateLeadRequest(
    val status: String?,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long?
)

/**
 * Request payload for updating lead status
 */
data class UpdateStatusRequest(
    val status: String
)

/**
 * Request payload for updating lead value
 */
data class UpdateValueRequest(
    val estimatedValue: Long
)

/**
 * Pagination info
 */
data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)

/**
 * Response from lead list API
 */
data class LeadListResponse(
    val leads: List<LeadDto>,
    val pagination: PaginationInfo
)

/**
 * Full lead detail response — includes AI estimation breakdown
 */
data class LeadDetailDto(
    val id: String,
    val source: String,
    val status: String,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
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

/**
 * Pipeline summary for dashboard widget
 */
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
