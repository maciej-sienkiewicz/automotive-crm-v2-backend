package pl.detailing.crm.leads.snapshot

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.query.LeadDto
import pl.detailing.crm.leads.query.toDto
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

/**
 * Loads the full lead DTO for the WebSocket bridge. The payload must match what the
 * REST API returns — the frontend replaces its cached row with it verbatim.
 */
@Service
class LeadSnapshotService(
    private val leadRepository: LeadRepository,
    private val itemRepository: LeadServiceItemRepository,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val conversationStates: LeadConversationStateService
) {

    @Transactional(readOnly = true)
    fun getLeadDto(studioId: StudioId, leadId: LeadId): LeadDto? {
        val lead = leadRepository.findByIdAndStudioId(leadId.value, studioId.value) ?: return null
        // Ten sam kształt co REST: front podmienia wiersz w tabeli tym payloadem
        // jeden do jednego, więc brak tagów kasowałby je na ekranie po każdej zmianie.
        return lead.toDto(
            itemRepository.findByLeadIdOrderByCreatedAtAsc(lead.id),
            tagService.tagsOf(lead.id),
            tagCatalog.labelsByCode(studioId),
            // Stan rozmowy też: bez niego podmiana wiersza po WebSockecie gasiłaby
            // plakietkę „do odpisania", która chwilę wcześniej tam była.
            conversationStates.stateOf(studioId.value, lead)
        )
    }
}
