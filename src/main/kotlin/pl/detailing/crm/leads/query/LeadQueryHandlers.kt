package pl.detailing.crm.leads.query

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.util.UUID

@Service
class LeadQueryHandlers(
    private val leadRepository: LeadRepository,
    private val itemRepository: LeadServiceItemRepository,
    private val historyRepository: LeadStatusHistoryRepository
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
        val itemsByLead = itemRepository.findByLeadIdIn(result.content.map { it.id })
            .groupBy { it.leadId }
        return LeadPageDto(
            items = result.content.map { it.toDto(itemsByLead[it.id].orEmpty()) },
            total = result.totalElements,
            page = result.number,
            pageSize = result.size
        )
    }

    @Transactional(readOnly = true)
    fun get(studioId: StudioId, leadId: UUID): LeadDto {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        return lead.toDto(itemRepository.findByLeadIdOrderByCreatedAtAsc(lead.id))
    }

    @Transactional(readOnly = true)
    fun statusHistory(studioId: StudioId, leadId: UUID): List<LeadStatusHistoryDto> {
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
        return historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId).map { it.toDto() }
    }
}
