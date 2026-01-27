package pl.detailing.crm.leads.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.infrastructure.LeadRepository

@Service
class ListLeadsHandler(
    private val leadRepository: LeadRepository
) {
    private val log = LoggerFactory.getLogger(ListLeadsHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListLeadsQuery): ListLeadsResult =
        withContext(Dispatchers.IO) {
            // Create pageable
            val pageable = PageRequest.of(query.page - 1, query.limit)

            // Query with filters
            val page = leadRepository.findByStudioIdWithFilters(
                studioId = query.studioId.value,
                statuses = query.statuses?.takeIf { it.isNotEmpty() }?.map { it.name },
                sources = query.sources?.takeIf { it.isNotEmpty() }?.map { it.name },
                search = query.search?.takeIf { it.isNotBlank() },
                pageable = pageable
            )

            // Convert to domain
            val leads = page.content.map { it.toDomain() }

            log.debug("[LEADS] Listed leads: studioId={}, page={}, total={}",
                query.studioId.value, query.page, page.totalElements)

            ListLeadsResult(
                leads = leads,
                currentPage = query.page,
                totalPages = page.totalPages,
                totalItems = page.totalElements,
                itemsPerPage = query.limit
            )
        }
}

data class ListLeadsResult(
    val leads: List<Lead>,
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Long,
    val itemsPerPage: Int
)
