package pl.detailing.crm.leads.query

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommAttachmentMeta
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.attachment.LeadAttachmentEntity
import pl.detailing.crm.leads.attachment.LeadAttachmentRepository
import pl.detailing.crm.leads.callback.LeadCallbackEntity
import pl.detailing.crm.leads.callback.LeadCallbackRepository
import pl.detailing.crm.leads.conversation.LeadConversationState
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.util.UUID

@Service
class LeadQueryHandlers(
    private val leadRepository: LeadRepository,
    private val itemRepository: LeadServiceItemRepository,
    private val historyRepository: LeadStatusHistoryRepository,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val conversationStates: LeadConversationStateService,
    private val messageRepository: CommMessageRepository,
    private val callbackRepository: LeadCallbackRepository,
    private val attachmentRepository: CommAttachmentRepository,
    private val leadAttachmentRepository: LeadAttachmentRepository
) {

    /**
     * Liczba na plakietce przy „Leady": nowe plus otwarte z zaległą odpowiedzią.
     * Jedno zapytanie COUNT — patrz [LeadRepository.countNeedingAttention].
     */
    @Transactional(readOnly = true)
    fun attentionCount(studioId: StudioId): Long =
        leadRepository.countNeedingAttention(
            studioId.value,
            listOf(LeadStatus.IN_PROGRESS, LeadStatus.CONFIRMED)
        )

    @Transactional(readOnly = true)
    fun list(
        studioId: StudioId,
        status: LeadStatus?,
        query: String?,
        awaitingReply: Boolean,
        page: Int,
        pageSize: Int
    ): LeadPageDto {
        val result = leadRepository.search(
            studioId.value,
            status,
            query?.trim()?.takeIf { it.isNotBlank() },
            awaitingReply,
            PageRequest.of(page.coerceAtLeast(0), pageSize.coerceIn(1, 100))
        )
        val leadIds = result.content.map { it.id }
        val itemsByLead = itemRepository.findByLeadIdIn(leadIds).groupBy { it.leadId }
        // Tagi całej strony jednym zapytaniem — inaczej lista na 50 leadów robi 50 dodatkowych.
        val tagsByLead = tagService.tagsOf(leadIds)
        val tagLabels = tagCatalog.labelsByCode(studioId)
        val states = conversationStates.statesOf(studioId.value, result.content)
        return LeadPageDto(
            items = result.content.map {
                it.toDto(
                    itemsByLead[it.id].orEmpty(),
                    tagsByLead[it.id].orEmpty(),
                    tagLabels,
                    states[it.id] ?: LeadConversationState.NONE
                )
            },
            total = result.totalElements,
            page = result.number,
            pageSize = result.size
        )
    }

    @Transactional(readOnly = true)
    fun get(studioId: StudioId, leadId: UUID): LeadDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        return lead.toDto(
            itemRepository.findByLeadIdOrderByCreatedAtAsc(lead.id),
            tagService.tagsOf(lead.id),
            tagCatalog.labelsByCode(studioId),
            conversationStates.stateOf(studioId.value, lead)
        )
    }

    /**
     * Pełna oś czasu leada: zmiany statusu, korespondencja i odnotowane telefony,
     * w jednej liście uporządkowanej chronologicznie.
     *
     * Wcześniej ten endpoint zwracał wyłącznie zmiany statusu, więc lead po wymianie
     * trzech maili opisany był dwiema linijkami i milczał o tym, co w nim najważniejsze:
     * o co klient pytał, kiedy odpisaliśmy i co odpowiedział. Te fakty istniały, tylko
     * w wątku poczty — czyli wszędzie, byle nie tam, gdzie użytkownik ich szukał.
     *
     * Sortowanie należy do serwera, a nie do przeglądarki: to ono decyduje, co
     * użytkownik uzna za przebieg sprawy, i ma być jedno dla wszystkich odbiorców.
     * Remisy (mail i zmiana statusu w tej samej sekundzie — odpowiedź STEMPLUJE
     * status, więc dzieje się to przy każdej pierwszej odpowiedzi) rozstrzygamy na
     * korzyść wiadomości: to ona jest przyczyną, status skutkiem.
     *
     * NIE KAŻDY STATUS ZASŁUGUJE NA WIERSZ — patrz [ECHOED_STATUSES]. Oś czasu ma
     * opowiadać, co się w sprawie wydarzyło, a nie protokołować każdy zapis w bazie.
     */
    @Transactional(readOnly = true)
    fun timeline(studioId: StudioId, leadId: UUID): List<LeadTimelineEntryDto> {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val statuses = historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId)
            .filterNot { it.toStatus in ECHOED_STATUSES }
            .map { it.toTimelineEntry() }
        val callbacks = callbackRepository.findByLeadIdOrderByCreatedAtAsc(leadId).map { it.toTimelineEntry() }
        val threadMessages = lead.threadId
            ?.let { messageRepository.findByThreadIdOrderBySentAtAsc(it) }
            .orEmpty()

        // Załączniki wiadomości z wątku czytamy wprost ze skrzynki, jednym zapytaniem:
        // dzięki temu widać także pliki z DALSZEJ korespondencji, nie tylko te z
        // wiadomości, która leada założyła. Obrazki osadzone w treści (`cid:`) to
        // elementy układu maila — logo w stopce — a nie coś, co klient przysłał.
        val attachmentsByMessage = if (threadMessages.isEmpty()) emptyMap() else
            attachmentRepository.findMetaByMessageIdIn(threadMessages.map { it.id })
                .filterNot { it.isInline }
                .groupBy { it.messageId }

        val messages = threadMessages.map {
            it.toTimelineEntry(lead, attachmentsByMessage[it.id].orEmpty().map(::toDto))
        }

        // Pliki podpięte do leada, których wiadomości nie ma na osi — tak wygląda
        // zgłoszenie z formularza WWW: lead świadomie nie ma wątku (wątek należy do
        // robota, nie do klienta), a to właśnie tam klient najczęściej dokłada zdjęcia.
        // Bez tego wpisu jedyny ślad po nich byłby w skrzynce robota.
        val orphanEntries = leadAttachmentRepository.findByLeadIdOrderByReceivedAtAsc(leadId)
            .filterNot { link -> threadMessages.any { it.id == link.messageId } }
            .groupBy { it.messageId }
            .map { (messageId, links) -> links.toTimelineEntry(messageId) }

        return (messages + orphanEntries + callbacks + statuses).sortedWith(
            compareBy({ it.at }, { KIND_ORDER.indexOf(it.kind) })
        )
    }

    private fun toDto(meta: CommAttachmentMeta) =
        LeadTimelineAttachmentDto(
            id = meta.id.toString(),
            fileName = meta.fileName,
            contentType = meta.contentType,
            sizeBytes = meta.sizeBytes
        )

    /** Jeden wpis na wiadomość spoza wątku — tyle, ile o niej wiemy: pliki i kiedy przyszły. */
    private fun List<LeadAttachmentEntity>.toTimelineEntry(messageId: UUID) = LeadTimelineEntryDto(
        id = "attachments-$messageId",
        kind = "ATTACHMENTS",
        at = first().receivedAt,
        actorName = null,
        attachments = map {
            LeadTimelineAttachmentDto(
                id = it.attachmentId.toString(),
                fileName = it.fileName,
                contentType = it.contentType,
                sizeBytes = it.sizeBytes
            )
        }
    )

    private fun LeadStatusHistoryEntity.toTimelineEntry() = LeadTimelineEntryDto(
        id = id.toString(),
        kind = "STATUS",
        at = createdAt,
        actorName = changedByName,
        toStatus = toStatus.name,
        fromStatus = fromStatus?.name,
        lostReasonLabel = lostReasonCode?.label
    )

    private fun LeadCallbackEntity.toTimelineEntry() = LeadTimelineEntryDto(
        id = id.toString(),
        kind = "CALLBACK",
        at = createdAt,
        actorName = calledByName,
        note = note
    )

    private fun CommMessageEntity.toTimelineEntry(
        lead: LeadEntity,
        attachments: List<LeadTimelineAttachmentDto>
    ) = LeadTimelineEntryDto(
        id = id.toString(),
        kind = if (direction == CommDirection.INBOUND) "INBOUND_MESSAGE" else "OUTBOUND_MESSAGE",
        at = sentAt,
        // Po stronie klienta nazwa z nagłówka bywa pusta — wtedy zostaje to, pod czym
        // lead jest znany w CRM-ie, a w ostateczności sam adres.
        actorName = if (direction == CommDirection.INBOUND) {
            fromName ?: lead.customerName ?: fromEmail
        } else {
            fromName
        },
        subject = subject,
        // Wersja bez cytatów i stopek; surowy tekst dopiero wtedy, gdy oczyszczonego
        // nie ma. Odpowiedź klienta to zwykle jedno zdanie i kopia całej rozmowy pod
        // spodem — pokazanie tej kopii w podglądzie zamieniłoby oś czasu w kolejny
        // przebieg tego samego wątku.
        body = (bodyTextClean?.takeIf { it.isNotBlank() } ?: bodyText)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TIMELINE_BODY),
        attachments = attachments
    )

    companion object {
        /**
         * Kolejność zdarzeń o tym samym znaczniku czasu. Wiadomość przed statusem,
         * bo to odpowiedź powoduje przejście na „W kontakcie", a nie odwrotnie.
         */
        private val KIND_ORDER = listOf("INBOUND_MESSAGE", "ATTACHMENTS", "OUTBOUND_MESSAGE", "CALLBACK", "STATUS")

        /**
         * Statusy, które na osi czasu są ECHEM zdarzenia stojącego tuż obok, więc nie
         * niosą nic własnego.
         *
         * „Nowy" powstaje razem z leadem, czyli w tej samej sekundzie co pierwsza
         * wiadomość albo pierwszy telefon — a te już otwierają oś czasu. „W kontakcie"
         * jest stemplowane przez naszą odpowiedź (patrz LeadFirstResponseListener
         * i RecordLeadCallbackHandler), więc powtarza wiersz wyżej innymi słowami.
         * Dwa wiersze na jedno wydarzenie rozciągają oś i każą czytelnikowi za każdym
         * razem rozstrzygać, czy właśnie nie przeoczył czegoś nowego.
         *
         * Reszta statusów zostaje, bo każdy z nich jest decyzją, której korespondencja
         * nie widzi: rezerwacja, przegrana z powodem, wizyta zrealizowana, nieodbyta.
         *
         * Filtrujemy WIDOK, nie dane — tabela lead_status_history zostaje nietknięta
         * i analityka lejka liczy dalej wszystkie przejścia.
         */
        private val ECHOED_STATUSES = setOf(LeadStatus.NEW, LeadStatus.IN_PROGRESS)

        /**
         * Sufit treści w podglądzie. Cała korespondencja jedzie w jednej odpowiedzi,
         * a wklejona oferta na trzy strony obciążałaby każde otwarcie leada.
         */
        private const val MAX_TIMELINE_BODY = 4_000
    }
}
