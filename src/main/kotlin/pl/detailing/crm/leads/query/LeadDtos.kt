package pl.detailing.crm.leads.query

import pl.detailing.crm.shared.pii.Pii
import pl.detailing.crm.leads.conversation.LeadConversationState
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import java.time.Instant

/** Full lead row — the shape the list, the detail panel and WebSocket updates share. */
data class LeadDto(
    val id: String,
    val source: String,
    val status: String,
    @Pii val contactIdentifier: String,
    @Pii val customerName: String?,
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
    /** NULL dla sugestii z wyceną niestandardową, która czeka na kwotę. */
    val priceGross: Long?,
    /** Netto i stawka VAT — null dla pozycji wycenionych przed wprowadzeniem tych pól. */
    val priceNet: Long?,
    val vatRate: Int?,
    val note: String?,
    val quantity: Int,
    val totalGross: Long,
    /** SUGGESTED (podsunięte przez AI) | ACCEPTED (część wyceny). */
    val status: String,
    /** MANUAL | AI — źródło pozycji; AI + SUGGESTED daje badge „Sugerowane". */
    val source: String,
    /** CATALOG | HISTORY | MANUAL | PENDING — skąd cena; PENDING = trzeba podać kwotę. */
    val priceSource: String
)

/**
 * Jedno zdarzenie na osi czasu leada.
 *
 * Do tej pory „Historia" pokazywała wyłącznie zmiany statusu, więc lead po wymianie
 * trzech maili opisany był dwiema linijkami — „Nowy", „W kontakcie" — i nie dało się
 * z niego odczytać ani tego, o co klient pytał, ani kiedy odpisaliśmy, ani co
 * odpowiedział. Najważniejsze fakty leżały w wątku poczty, czyli gdzie indziej.
 *
 * Typ jest sumą kilku rodzajów zdarzeń, więc pola poza [kind], [at] i [actorName]
 * są opcjonalne z definicji: wypełnia się ta garść, która ma sens dla danego rodzaju.
 * Alternatywą byłyby cztery osobne listy do posortowania po stronie przeglądarki —
 * czyli przeniesienie tam decyzji o kolejności, która jest decyzją serwera.
 */
data class LeadTimelineEntryDto(
    val id: String,
    /** STATUS | INBOUND_MESSAGE | OUTBOUND_MESSAGE | CALLBACK */
    val kind: String,
    val at: Instant,
    /** Kto: użytkownik studia, klient albo null dla zmian automatycznych. */
    val actorName: String?,
    val toStatus: String? = null,
    val fromStatus: String? = null,
    val lostReasonLabel: String? = null,
    val subject: String? = null,
    /**
     * Treść wiadomości bez cytatów i stopek — „pokaż wiadomość" ma co wyświetlić
     * bez drugiego żądania. Wątek leada to kilka wiadomości, więc koszt jest znikomy,
     * a osobny endpoint na każdą z nich oznaczałby zapytanie na kliknięcie.
     */
    val body: String? = null,
    /** Notatka przy odnotowanym telefonie — opcjonalna, jak samo pole. */
    val note: String? = null
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
    totalGross = (priceGross ?: 0L) * quantity,
    status = status.name,
    source = source.name,
    priceSource = priceSource.name
)

/**
 * Tagi przychodzą ze słownika studia (jest edytowalny), powody przegranej wciąż z enuma
 * — te ostatnie są osią raportu, a nie polem do wpisywania czegokolwiek.
 */
fun leadDictionaries(tags: List<DictionaryEntryDto>): LeadDictionariesDto = LeadDictionariesDto(
    tags = tags,
    lostReasons = LeadLostReason.entries.map { DictionaryEntryDto(it.name, it.label) }
)
