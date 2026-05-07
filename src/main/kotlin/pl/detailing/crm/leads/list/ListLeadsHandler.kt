package pl.detailing.crm.leads.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.estimation.infrastructure.RelatedVisit
import pl.detailing.crm.leads.infrastructure.LeadRepository

data class LeadListItem(
    val lead: Lead,
    val relatedVisits: List<RelatedVisit>,
    val aiReasoning: String?
)

@Service
class ListLeadsHandler(
    private val leadRepository: LeadRepository,
    private val leadEstimationRepository: LeadEstimationRepository
) {
    private val log = LoggerFactory.getLogger(ListLeadsHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListLeadsQuery): ListLeadsResult =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(query.page - 1, query.limit)

            val page = leadRepository.findByStudioIdWithFilters(
                studioId = query.studioId.value,
                statuses = query.statuses?.takeIf { it.isNotEmpty() }?.map { it.name },
                sources = query.sources?.takeIf { it.isNotEmpty() }?.map { it.name },
                search = query.search?.takeIf { it.isNotBlank() },
                pageable = pageable
            )

            val leads = page.content.map { it.toDomain() }

            // Batch-load all estimations for current page — single query, no N+1
            val leadIds = leads.map { it.id.value }
            val estimationsByLeadId = if (leadIds.isNotEmpty()) {
                leadEstimationRepository.findByStudioIdAndLeadIdIn(
                    studioId = query.studioId.value,
                    leadIds = leadIds
                ).associateBy { it.leadId }
            } else emptyMap()

            val items = leads.map { lead ->
                val est = estimationsByLeadId[lead.id.value]
                LeadListItem(
                    lead = lead,
                    relatedVisits = est?.relatedVisits ?: emptyList(),
                    aiReasoning = est?.aiReasoning
                )
            }

            log.debug("[LEADS] Listed leads: studioId={}, page={}, total={}",
                query.studioId.value, query.page, page.totalElements)

            ListLeadsResult(
                items = items,
                currentPage = query.page,
                totalPages = page.totalPages,
                totalItems = page.totalElements,
                itemsPerPage = query.limit
            )
        }
}

data class ListLeadsResult(
    val items: List<LeadListItem>,
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Long,
    val itemsPerPage: Int
)
