package pl.detailing.crm.leads

import java.time.Instant
import pl.detailing.crm.leads.customer.CustomerSnapshot
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.get.EstimationItemResult
import pl.detailing.crm.leads.get.EstimationResult
import pl.detailing.crm.leads.get.GetLeadResult
import pl.detailing.crm.leads.list.LeadListItem
import pl.detailing.crm.leads.userquote.save.SaveUserQuoteResult
import pl.detailing.crm.leads.userquote.save.UserQuoteItemResult
import pl.detailing.crm.shared.LeadSource

data class RelatedVisitDto(
    val id: String,
    val title: String?
)

data class CustomerSnapshotDto(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?
)

data class LeadServiceTagDto(
    val serviceId: String?,
    val serviceName: String
)

data class LeadDto(
    val id: String,
    val source: String?,
    val status: String,
    val contactIdentifier: String?,
    val customerName: String?,
    val initialMessage: String?,
    val summary: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val relatedVisits: List<RelatedVisitDto>,
    val assignedCustomer: CustomerSnapshotDto?,
    val appointmentId: String?,
    val visitId: String?,
    val assignedUserId: String? = null,
    val assignedUserName: String? = null,
    val lostReason: String? = null,
    val serviceTags: List<LeadServiceTagDto> = emptyList()
)

fun Lead.toDto(
    relatedVisits: List<RelatedVisitDto> = emptyList(),
    summary: String? = null,
    assignedCustomer: CustomerSnapshotDto? = null,
    serviceTags: List<LeadServiceTagDto> = emptyList()
): LeadDto = LeadDto(
    id = this.id.toString(),
    source = this.source.name,
    status = this.status.name,
    contactIdentifier = this.contactIdentifier,
    customerName = this.customerName,
    initialMessage = this.initialMessage,
    summary = summary,
    createdAt = this.createdAt,
    updatedAt = this.updatedAt,
    estimatedValue = this.estimatedValue,
    requiresVerification = this.requiresVerification,
    vehicleBrand = this.vehicleBrand,
    vehicleModel = this.vehicleModel,
    relatedVisits = relatedVisits,
    assignedCustomer = assignedCustomer,
    appointmentId = this.appointmentId?.toString(),
    visitId = this.visitId?.toString(),
    assignedUserId = this.assignedUserId?.toString(),
    assignedUserName = this.assignedUserName,
    lostReason = this.lostReason,
    serviceTags = serviceTags
)

fun LeadListItem.toDto(): LeadDto = lead.toDto(
    relatedVisits = relatedVisits.map { RelatedVisitDto(id = it.id, title = it.title) },
    summary = aiSummary,
    assignedCustomer = assignedCustomer?.toDto(),
    serviceTags = serviceTags.map { LeadServiceTagDto(serviceId = it.serviceId?.toString(), serviceName = it.serviceName) }
)

fun CustomerSnapshot.toDto() = CustomerSnapshotDto(
    id = id,
    firstName = firstName,
    lastName = lastName,
    email = email,
    phone = phone
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

data class AssignCustomerRequest(
    val customerId: String?
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
    val assignedCustomer: CustomerSnapshotDto?,
    val appointmentId: String?,
    val visitId: String?,
    val assignedUserId: String?,
    val assignedUserName: String?,
    val lostReason: String?,
    val serviceTags: List<LeadServiceTagDto>,
    val estimation: LeadEstimationDto?,
    val userQuote: LeadUserQuoteDto?
)

data class LeadEstimationDto(
    val id: String,
    val status: String,
    val extractedNeeds: List<String>,
    val matchedItems: List<LeadEstimationItemDto>,
    val unmatchedNeeds: List<String>,
    val totalNet: Long,
    val totalGross: Long,
    val relatedVisits: List<RelatedVisitDto>,
    val aiSummary: String?,
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

data class LeadUserQuoteDto(
    val id: String,
    val items: List<LeadUserQuoteItemDto>,
    val totalNet: Long,
    val totalGross: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class LeadUserQuoteItemDto(
    val id: String,
    val serviceId: String?,
    val serviceName: String,
    val priceNet: Long,
    val vatRate: Int,
    val priceGross: Long
)

data class SaveUserQuoteRequest(
    val items: List<SaveUserQuoteItemRequest>
)

data class SaveUserQuoteItemRequest(
    val serviceId: String?,
    val serviceName: String?,
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
    assignedCustomer = assignedCustomer?.toDto(),
    appointmentId = appointmentId?.toString(),
    visitId = visitId?.toString(),
    assignedUserId = assignedUserId?.toString(),
    assignedUserName = assignedUserName,
    lostReason = lostReason,
    serviceTags = serviceTags.map { LeadServiceTagDto(serviceId = it.serviceId?.toString(), serviceName = it.serviceName) },
    estimation = estimation?.toDto(),
    userQuote = userQuote?.toDto()
)

fun EstimationResult.toDto() = LeadEstimationDto(
    id = id.toString(),
    status = status,
    extractedNeeds = extractedNeeds,
    matchedItems = matchedItems.map { it.toDto() },
    unmatchedNeeds = unmatchedNeeds,
    totalNet = totalNet,
    totalGross = totalGross,
    relatedVisits = relatedVisits.map { RelatedVisitDto(id = it.id, title = it.title) },
    aiSummary = aiSummary,
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

fun SaveUserQuoteResult.toDto() = LeadUserQuoteDto(
    id = id,
    items = items.map { it.toDto() },
    totalNet = totalNet,
    totalGross = totalGross,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun UserQuoteItemResult.toDto() = LeadUserQuoteItemDto(
    id = id,
    serviceId = serviceId,
    serviceName = serviceName,
    priceNet = priceNet,
    vatRate = vatRate,
    priceGross = priceGross
)

data class PipelineSummaryDto(
    val awaitingFirstContactCount: Int,
    val avgWaitingTimeMinutes: Long,
    val conversionRateThisMonth: Double,
    val conversionRateTrendPp: Double,
    val convertedValueThisMonth: Long,
    val convertedCountThisMonth: Int,
    val atRiskValue: Long,
    val atRiskCount: Int,
    val newLeadsCount: Int
)

// ── Lead → Appointment creation ──────────────────────────────────────────────

data class CreateLeadAppointmentRequest(
    val customer: LeadAppointmentCustomerRequest,
    val vehicle: LeadAppointmentVehicleRequest,
    val services: List<LeadAppointmentServiceRequest>,
    val schedule: LeadAppointmentScheduleRequest,
    val appointmentTitle: String?,
    val appointmentColorId: String,
    val note: String?,
    val sendReminderSms: Boolean = false
)

data class LeadAppointmentScheduleRequest(
    val isAllDay: Boolean,
    val startDateTime: java.time.Instant,
    val endDateTime: java.time.Instant
)

enum class LeadAppointmentCustomerMode { EXISTING, NEW, UPDATE }
enum class LeadAppointmentVehicleMode { EXISTING, NEW, UPDATE, NONE }

data class LeadAppointmentCustomerRequest(
    val mode: LeadAppointmentCustomerMode,
    val id: String?,
    val newData: LeadAppointmentNewCustomerData?,
    val updateData: LeadAppointmentNewCustomerData?
)

data class LeadAppointmentNewCustomerData(
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val email: String?,
    val company: LeadAppointmentCompanyData?
)

data class LeadAppointmentCompanyData(
    val name: String?,
    val nip: String?,
    val regon: String?,
    val address: String?
)

data class LeadAppointmentVehicleRequest(
    val mode: LeadAppointmentVehicleMode,
    val id: String?,
    val newData: LeadAppointmentNewVehicleData?,
    val updateData: LeadAppointmentNewVehicleData?
)

data class LeadAppointmentNewVehicleData(
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?
)

data class LeadAppointmentServiceRequest(
    val id: String,
    val serviceId: String?,
    val serviceName: String?,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: LeadAppointmentAdjustmentRequest,
    val note: String?
)

data class LeadAppointmentAdjustmentRequest(
    val type: pl.detailing.crm.appointment.domain.AdjustmentType,
    val value: Double
)

data class GenerateQuoteReplyResponse(
    val title: String,
    val reply: String
)

data class CreateLeadAppointmentResponse(
    val appointmentId: String,
    val customerId: String,
    val vehicleId: String?,
    val leadStatus: String,
    val totalNet: Long,
    val totalGross: Long,
    val totalVat: Long
)

// ── Lead assignment ──────────────────────────────────────────────────────────

data class AssignLeadUserRequest(
    val userId: String?,
    val userName: String?
)

// ── Lost reason ──────────────────────────────────────────────────────────────

data class UpdateLostReasonRequest(
    val lostReason: String?
)

// ── Service tags ─────────────────────────────────────────────────────────────

data class SetServiceTagsRequest(
    val tags: List<ServiceTagInput>
)

data class ServiceTagInput(
    val serviceId: String?,
    val serviceName: String
)

// ── Service analytics ────────────────────────────────────────────────────────

data class ServiceAnalyticsItemDto(
    val serviceId: String?,
    val serviceName: String,
    val wonCount: Int,
    val lostCount: Int,
    val totalCount: Int,
    val winRate: Double
)

// ── Comments ─────────────────────────────────────────────────────────────────

data class LeadCommentDto(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedBy: String?,
    val updatedByName: String?,
    val updatedAt: Instant?
)

data class AddLeadCommentRequest(
    val content: String
)

data class UpdateLeadCommentRequest(
    val content: String
)

// ── Status history ────────────────────────────────────────────────────────────

data class HistoryFieldChangeDto(
    val field: String,
    val oldValue: String?,
    val newValue: String?
)

data class LeadStatusHistoryEntryDto(
    val changedAt: Instant,
    val action: String,
    val changedByUserId: String,
    val changedByName: String,
    val changes: List<HistoryFieldChangeDto>
)

// ── Employee stats ───────────────────────────────────────────────────────────

data class EmployeeStatsItemDto(
    val userId: String,
    val userName: String,
    val totalLeads: Int,
    val converted: Int,
    val lost: Int,
    val conversionRate: Double,
    val avgLeadValueCents: Long
)
