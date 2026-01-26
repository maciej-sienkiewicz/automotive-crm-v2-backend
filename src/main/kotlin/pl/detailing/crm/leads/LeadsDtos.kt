package pl.detailing.crm.leads

import pl.detailing.crm.leads.domain.Lead
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
    val createdAt: String?,
    val updatedAt: String?,
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
    createdAt = this.createdAt.toString(),
    updatedAt = this.updatedAt.toString(),
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
