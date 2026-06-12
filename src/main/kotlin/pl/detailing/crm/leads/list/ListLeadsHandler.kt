package pl.detailing.crm.leads.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.customer.CustomerSnapshot
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.estimation.infrastructure.RelatedVisit
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.services.LeadServiceTagEntity
import pl.detailing.crm.leads.services.LeadServiceTagRepository

data class LeadListItem(
    val lead: Lead,
    val relatedVisits: List<RelatedVisit>,
    val aiSummary: String?,
    val assignedCustomer: CustomerSnapshot?,
    val serviceTags: List<LeadServiceTagEntity> = emptyList()
)

@Service
class ListLeadsHandler(
    private val leadRepository: LeadRepository,
    private val leadEstimationRepository: LeadEstimationRepository,
    private val customerRepository: CustomerRepository,
    private val leadServiceTagRepository: LeadServiceTagRepository
) {
    private val log = LoggerFactory.getLogger(ListLeadsHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: ListLeadsQuery): ListLeadsResult =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(query.page - 1, query.limit)

            val hasExtendedFilters = query.valueMin != null || query.valueMax != null
                || query.assignedUserId != null || !query.serviceIds.isNullOrEmpty()

            val page = if (hasExtendedFilters) {
                leadRepository.findByStudioIdWithExtendedFilters(
                    studioId = query.studioId.value,
                    statuses = query.statuses?.takeIf { it.isNotEmpty() }?.map { it.name },
                    sources = query.sources?.takeIf { it.isNotEmpty() }?.map { it.name },
                    search = query.search?.takeIf { it.isNotBlank() },
                    dateFrom = query.dateFrom,
                    dateTo = query.dateTo,
                    valueMin = query.valueMin,
                    valueMax = query.valueMax,
                    assignedUserId = query.assignedUserId?.toString(),
                    serviceIds = query.serviceIds?.takeIf { it.isNotEmpty() },
                    pageable = pageable
                )
            } else {
                leadRepository.findByStudioIdWithFilters(
                    studioId = query.studioId.value,
                    statuses = query.statuses?.takeIf { it.isNotEmpty() }?.map { it.name },
                    sources = query.sources?.takeIf { it.isNotEmpty() }?.map { it.name },
                    search = query.search?.takeIf { it.isNotBlank() },
                    dateFrom = query.dateFrom,
                    dateTo = query.dateTo,
                    pageable = pageable
                )
            }

            val leads = page.content.map { it.toDomain() }

            // Batch-load all estimations for current page — single query, no N+1
            val leadIds = leads.map { it.id.value }
            val estimationsByLeadId = if (leadIds.isNotEmpty()) {
                leadEstimationRepository.findByStudioIdAndLeadIdIn(
                    studioId = query.studioId.value,
                    leadIds = leadIds
                ).associateBy { it.leadId }
            } else emptyMap()

            // Batch-load customer snapshots for leads that have an assigned customer
            val customerIds = leads.mapNotNull { it.customerId?.value }.distinct()
            val customersById = if (customerIds.isNotEmpty()) {
                customerRepository.findByIdsAndStudioId(
                    ids = customerIds,
                    studioId = query.studioId.value
                ).associateBy { it.id }
            } else emptyMap()

            // Batch-load service tags for current page
            val serviceTagsByLeadId = if (leadIds.isNotEmpty()) {
                leadServiceTagRepository.findByLeadIdIn(leadIds).groupBy { it.leadId }
            } else emptyMap()

            val items = leads.map { lead ->
                val est = estimationsByLeadId[lead.id.value]
                val customer = lead.customerId?.value?.let { customersById[it] }
                LeadListItem(
                    lead = lead,
                    relatedVisits = est?.relatedVisits ?: emptyList(),
                    aiSummary = est?.aiSummary,
                    assignedCustomer = customer?.let {
                        CustomerSnapshot(
                            id = it.id.toString(),
                            firstName = it.firstName,
                            lastName = it.lastName,
                            email = it.email,
                            phone = it.phone
                        )
                    },
                    serviceTags = serviceTagsByLeadId[lead.id.value] ?: emptyList()
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
