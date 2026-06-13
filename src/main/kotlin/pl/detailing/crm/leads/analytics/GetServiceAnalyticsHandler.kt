package pl.detailing.crm.leads.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteRepository
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class GetServiceAnalyticsQuery(
    val studioId: StudioId,
    val sources: List<LeadSource>?,
    val dateFrom: Instant?,
    val dateTo: Instant?
)

data class ServiceAnalyticsEntry(
    val serviceId: String?,
    val serviceName: String,
    val wonCount: Int,
    val lostCount: Int,
    val totalCount: Int,
    val winRate: Double
)

@Service
class GetServiceAnalyticsHandler(
    private val leadRepository: LeadRepository,
    private val userQuoteRepository: LeadUserQuoteRepository,
    private val estimationRepository: LeadEstimationRepository
) {
    private val WON_STATUSES = setOf(LeadStatus.CONFIRMED, LeadStatus.COMPLETED)
    private val LOST_STATUSES = setOf(LeadStatus.LOST, LeadStatus.NO_SHOW)

    @Transactional(readOnly = true)
    suspend fun handle(query: GetServiceAnalyticsQuery): List<ServiceAnalyticsEntry> =
        withContext(Dispatchers.IO) {
            val allLeads = leadRepository.findByStudioIdWithSourceFilter(
                studioId = query.studioId.value,
                sources = query.sources?.takeIf { it.isNotEmpty() }?.map { it.name },
                dateFrom = query.dateFrom,
                dateTo = query.dateTo
            )

            if (allLeads.isEmpty()) return@withContext emptyList()

            val leadIds = allLeads.map { it.id }
            val leadStatusById = allLeads.associate { it.id to it.status }

            // Batch-load user quotes and estimations
            val quotesByLeadId = userQuoteRepository.findByLeadIdIn(leadIds).associateBy { it.leadId }
            val estimationsByLeadId = estimationRepository
                .findByStudioIdAndLeadIdIn(query.studioId.value, leadIds)
                .associateBy { it.leadId }

            // Per-lead: pick userQuote if exists, else estimation items
            data class ServiceKey(val serviceId: UUID?, val serviceName: String)

            val serviceToLeadIds = mutableMapOf<ServiceKey, MutableList<UUID>>()
            val noQuoteLeadIds = mutableListOf<UUID>()

            for (lead in allLeads) {
                val quote = quotesByLeadId[lead.id]
                val items = when {
                    quote != null -> quote.items.map { ServiceKey(it.serviceId, it.serviceName) }
                    else -> estimationsByLeadId[lead.id]?.items
                        ?.map { ServiceKey(it.serviceId, it.serviceName) }
                        ?: emptyList()
                }
                if (items.isEmpty()) {
                    noQuoteLeadIds.add(lead.id)
                } else {
                    for (key in items.distinctBy { it }) {
                        serviceToLeadIds.getOrPut(key) { mutableListOf() }.add(lead.id)
                    }
                }
            }

            val entries = serviceToLeadIds.map { (key, leadIdsForService) ->
                val statuses = leadIdsForService.mapNotNull { leadStatusById[it] }
                val won = statuses.count { it in WON_STATUSES }
                val lost = statuses.count { it in LOST_STATUSES }
                val total = statuses.size
                ServiceAnalyticsEntry(
                    serviceId = key.serviceId?.toString(),
                    serviceName = key.serviceName,
                    wonCount = won,
                    lostCount = lost,
                    totalCount = total,
                    winRate = if (total > 0) won.toDouble() / total * 100.0 else 0.0
                )
            }.toMutableList()

            // Leads without any quote/estimation — count in totals without service breakdown
            if (noQuoteLeadIds.isNotEmpty()) {
                val statuses = noQuoteLeadIds.mapNotNull { leadStatusById[it] }
                val won = statuses.count { it in WON_STATUSES }
                val lost = statuses.count { it in LOST_STATUSES }
                entries.add(
                    ServiceAnalyticsEntry(
                        serviceId = null,
                        serviceName = "Brak wyceny",
                        wonCount = won,
                        lostCount = lost,
                        totalCount = statuses.size,
                        winRate = if (statuses.isNotEmpty()) won.toDouble() / statuses.size * 100.0 else 0.0
                    )
                )
            }

            entries.sortedByDescending { it.totalCount }
        }
}
