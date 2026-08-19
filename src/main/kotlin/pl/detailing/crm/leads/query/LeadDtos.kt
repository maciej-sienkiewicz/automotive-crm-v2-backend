package pl.detailing.crm.leads.query

import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.domain.LeadTag
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryEntity
import java.time.Instant

/** Full lead row — the shape the list, the detail panel and WebSocket updates share. */
data class LeadDto(
    val id: String,
    val source: String,
    val status: String,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val customerId: String?,
    val appointmentId: String?,
    val visitId: String?,
    val assignedUserId: String?,
    val assignedUserName: String?,
    val threadId: String?,
    /** Kody tagów — oś „o co pytają" w analityce. */
    val tags: List<String>,
    val tagLabels: List<String>,
    /** Rozpoznane z korespondencji przez LLM; null, gdy klient nie podał auta. */
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val lostReasonCode: String?,
    val lostReasonLabel: String?,
    val lostReason: String?,
    val services: List<LeadServiceItemDto>,
    val firstResponseAt: Instant?,
    val closedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class LeadServiceItemDto(
    val id: String,
    val serviceId: String?,
    val name: String,
    val priceGross: Long,
    val quantity: Int,
    val totalGross: Long
)

data class LeadStatusHistoryDto(
    val fromStatus: String?,
    val toStatus: String,
    val lostReasonLabel: String?,
    val changedByName: String?,
    val createdAt: Instant
)

data class LeadPageDto(
    val items: List<LeadDto>,
    val total: Long,
    val page: Int,
    val pageSize: Int
)

/** Dictionaries the frontend renders as pickers — one source of truth, the backend. */
data class LeadDictionariesDto(
    val tags: List<DictionaryEntryDto>,
    val lostReasons: List<DictionaryEntryDto>
)

data class DictionaryEntryDto(val code: String, val label: String)

fun LeadEntity.toDto(
    services: List<LeadServiceItemEntity>,
    tags: List<LeadTag> = emptyList()
): LeadDto = LeadDto(
    id = id.toString(),
    source = source.name,
    status = status.name,
    contactIdentifier = contactIdentifier,
    customerName = customerName,
    initialMessage = initialMessage,
    estimatedValue = estimatedValue,
    requiresVerification = requiresVerification,
    customerId = customerId?.toString(),
    appointmentId = appointmentId?.toString(),
    visitId = visitId?.toString(),
    assignedUserId = assignedUserId?.toString(),
    assignedUserName = assignedUserName,
    threadId = threadId?.toString(),
    tags = tags.map { it.name },
    tagLabels = tags.map { it.label },
    vehicleBrand = vehicleBrand,
    vehicleModel = vehicleModel,
    lostReasonCode = lostReasonCode?.name,
    lostReasonLabel = lostReasonCode?.label,
    lostReason = lostReason,
    services = services.map { it.toDto() },
    firstResponseAt = firstResponseAt,
    closedAt = closedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun LeadServiceItemEntity.toDto(): LeadServiceItemDto = LeadServiceItemDto(
    id = id.toString(),
    serviceId = serviceId?.toString(),
    name = name,
    priceGross = priceGross,
    quantity = quantity,
    totalGross = priceGross * quantity
)

fun LeadStatusHistoryEntity.toDto(): LeadStatusHistoryDto = LeadStatusHistoryDto(
    fromStatus = fromStatus?.name,
    toStatus = toStatus.name,
    lostReasonLabel = lostReasonCode?.label,
    changedByName = changedByName,
    createdAt = createdAt
)

fun leadDictionaries(): LeadDictionariesDto = LeadDictionariesDto(
    tags = LeadTag.entries.map { DictionaryEntryDto(it.name, it.label) },
    lostReasons = LeadLostReason.entries.map { DictionaryEntryDto(it.name, it.label) }
)
