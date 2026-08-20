package pl.detailing.crm.leads.query

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
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
    private val tagCatalog: LeadTagCatalogService
) {

    @Transactional(readOnly = true)
    fun list(
        studioId: StudioId,
        status: LeadStatus?,
        query: String?,
        page: Int,
        pageSize: Int
    ): LeadPageDto {
        val result = leadRepository.search(
            studioId.value,
            status,
            query?.trim()?.takeIf { it.isNotBlank() },
            PageRequest.of(page.coerceAtLeast(0), pageSize.coerceIn(1, 100))
        )
        val leadIds = result.content.map { it.id }
        val itemsByLead = itemRepository.findByLeadIdIn(leadIds).groupBy { it.leadId }
        // Tagi całej strony jednym zapytaniem — inaczej lista na 50 leadów robi 50 dodatkowych.
        val tagsByLead = tagService.tagsOf(leadIds)
        val tagLabels = tagCatalog.labelsByCode(studioId)
        return LeadPageDto(
            items = result.content.map {
                it.toDto(itemsByLead[it.id].orEmpty(), tagsByLead[it.id].orEmpty(), tagLabels)
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
            tagCatalog.labelsByCode(studioId)
        )
    }

    @Transactional(readOnly = true)
    fun statusHistory(studioId: StudioId, leadId: UUID): List<LeadStatusHistoryDto> {
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        return historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId).map { it.toDto() }
    }
}
