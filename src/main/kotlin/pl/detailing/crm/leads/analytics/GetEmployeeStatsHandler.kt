package pl.detailing.crm.leads.analytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class GetEmployeeStatsQuery(
    val studioId: StudioId,
    val dateFrom: Instant?,
    val dateTo: Instant?
)

data class EmployeeStatsEntry(
    val userId: String,
    val userName: String,
    val totalLeads: Int,
    val converted: Int,
    val lost: Int,
    val conversionRate: Double,
    val avgLeadValueCents: Long
)

@Service
class GetEmployeeStatsHandler(
    private val leadRepository: LeadRepository
) {
    private val CONVERTED_STATUSES = setOf(LeadStatus.CONFIRMED, LeadStatus.COMPLETED)
    private val LOST_STATUSES = setOf(LeadStatus.LOST, LeadStatus.NO_SHOW)

    @Transactional(readOnly = true)
    suspend fun handle(query: GetEmployeeStatsQuery): List<EmployeeStatsEntry> =
        withContext(Dispatchers.IO) {
            val allLeads = leadRepository.findByStudioIdWithSourceFilter(
                studioId = query.studioId.value,
                sources = null,
                dateFrom = query.dateFrom,
                dateTo = query.dateTo
            ).filter { it.assignedUserId != null }

            allLeads.groupBy { it.assignedUserId!! to (it.assignedUserName ?: it.assignedUserId.toString()) }
                .map { (key, leads) ->
                    val (userId, userName) = key
                    val converted = leads.count { it.status in CONVERTED_STATUSES }
                    val lost = leads.count { it.status in LOST_STATUSES }
                    val total = leads.size
                    val avgValue = if (total > 0) leads.sumOf { it.estimatedValue } / total else 0L
                    EmployeeStatsEntry(
                        userId = userId.toString(),
                        userName = userName,
                        totalLeads = total,
                        converted = converted,
                        lost = lost,
                        conversionRate = if (total > 0) converted.toDouble() / total * 100.0 else 0.0,
                        avgLeadValueCents = avgValue
                    )
                }.sortedByDescending { it.totalLeads }
        }
}
