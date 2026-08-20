package pl.detailing.crm.leads.query

import pl.detailing.crm.leads.conversation.LeadConversationState
import pl.detailing.crm.leads.domain.LeadLostReason
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
    /** Wartości z katalogu pojazdów; null, gdy nie rozpoznano. */
    val vehicleBrand: String?,
    val vehicleModel: String?,
    /** PENDING = rozpoznanie w toku (tabela pokazuje spinner), DONE = zakończone. */
    val vehicleDetectionStatus: String,
    val lostReasonCode: String?,
    val lostReasonLabel: String?,
    val lostReason: String?,
    val services: List<LeadServiceItemDto>,
    /**
     * Czyj ruch w rozmowie: AWAITING_OUR_REPLY | AWAITING_CLIENT_REPLY | NO_CONVERSATION.
     * Wyliczane z korespondencji, nie ustawiane ręcznie — to fakt, nie decyzja.
     */
    val replyState: String,
    /** Od kiedy trwa bieżące oczekiwanie; null, gdy nie ma na co czekać. */
    val waitingSince: Instant?,
    val lastInboundAt: Instant?,
    val lastOutboundAt: Instant?,
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
    /** Netto i stawka VAT — null dla pozycji wycenionych przed wprowadzeniem tych pól. */
    val priceNet: Long?,
    val vatRate: Int?,
    val note: String?,
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
    tagCodes: List<String> = emptyList(),
    /** Etykiety ze słownika studia; kod bez definicji wyświetla się sam jako ostatnia deska ratunku. */
    tagLabels: Map<String, String> = emptyMap(),
    conversation: LeadConversationState = LeadConversationState.NONE
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
    tags = tagCodes,
    tagLabels = tagCodes.map { tagLabels[it] ?: it },
    vehicleBrand = vehicleBrand,
    vehicleModel = vehicleModel,
    vehicleDetectionStatus = vehicleDetectionStatus.name,
    lostReasonCode = lostReasonCode?.name,
    lostReasonLabel = lostReasonCode?.label,
    lostReason = lostReason,
    services = services.map { it.toDto() },
    replyState = conversation.replyState.name,
    waitingSince = conversation.waitingSince,
    lastInboundAt = conversation.lastInboundAt,
    lastOutboundAt = conversation.lastOutboundAt,
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
    priceNet = priceNet,
    vatRate = vatRate,
    note = note,
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

/**
 * Tagi przychodzą ze słownika studia (jest edytowalny), powody przegranej wciąż z enuma
 * — te ostatnie są osią raportu, a nie polem do wpisywania czegokolwiek.
 */
fun leadDictionaries(tags: List<DictionaryEntryDto>): LeadDictionariesDto = LeadDictionariesDto(
    tags = tags,
    lostReasons = LeadLostReason.entries.map { DictionaryEntryDto(it.name, it.label) }
)
