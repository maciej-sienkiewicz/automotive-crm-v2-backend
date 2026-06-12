package pl.detailing.crm.leads.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.services.LeadServiceTagRepository
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant

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
    private val leadServiceTagRepository: LeadServiceTagRepository
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

            val leadStatusById = allLeads.associate { it.id to it.status }
            val leadIds = allLeads.map { it.id }

            val tags = leadServiceTagRepository.findByLeadIdIn(leadIds)

            // Group by service (serviceId + serviceName as key)
            data class ServiceKey(val serviceId: String?, val serviceName: String)

            val byService = tags.groupBy { tag ->
                ServiceKey(tag.serviceId?.toString(), tag.serviceName)
            }

            byService.map { (key, tagsForService) ->
                val statuses = tagsForService.mapNotNull { leadStatusById[it.leadId] }
                val won = statuses.count { it in WON_STATUSES }
                val lost = statuses.count { it in LOST_STATUSES }
                val total = statuses.size
                ServiceAnalyticsEntry(
                    serviceId = key.serviceId,
                    serviceName = key.serviceName,
                    wonCount = won,
                    lostCount = lost,
                    totalCount = total,
                    winRate = if (total > 0) won.toDouble() / total * 100.0 else 0.0
                )
            }.sortedByDescending { it.totalCount }
        }
}
