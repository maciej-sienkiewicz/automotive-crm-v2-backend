package pl.detailing.crm.leads.summary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Service
class GetPipelineSummaryHandler(
    private val leadRepository: LeadRepository
) {
    private val log = LoggerFactory.getLogger(GetPipelineSummaryHandler::class.java)

    @Transactional(readOnly = true)
    suspend fun handle(query: GetPipelineSummaryQuery): PipelineSummaryResult =
        withContext(Dispatchers.IO) {
            // Get all leads with optional source filter
            val allLeads = if (query.sourceFilter != null && query.sourceFilter.isNotEmpty()) {
                leadRepository.findByStudioIdWithSourceFilter(
                    studioId = query.studioId.value,
                    sources = query.sourceFilter
                )
            } else {
                leadRepository.findByStudioIdWithSourceFilter(
                    studioId = query.studioId.value,
                    sources = null
                )
            }

            // Filter by status
            val inProgress = allLeads.filter { it.status == LeadStatus.IN_PROGRESS }
            val converted = allLeads.filter { it.status == LeadStatus.CONVERTED }
            val abandoned = allLeads.filter { it.status == LeadStatus.ABANDONED }

            // Calculate total pipeline value (IN_PROGRESS only)
            val totalPipelineValue = inProgress.sumOf { it.estimatedValue }

            // Calculate this month's metrics
            val now = Instant.now()
            val zoneId = ZoneId.systemDefault()
            val startOfMonth = now.atZone(zoneId)
                .withDayOfMonth(1)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toInstant()

            val leadsThisMonth = allLeads.filter { it.createdAt >= startOfMonth }
            val leadsValueThisMonth = leadsThisMonth.sumOf { it.estimatedValue }

            val convertedThisMonth = converted.filter { 
                val updatedAt = it.updatedAt
                updatedAt >= startOfMonth
            }
            val convertedValueThisMonth = convertedThisMonth.sumOf { it.estimatedValue }

            // Calculate this week's conversions
            val startOfThisWeek = now.atZone(zoneId)
                .with(java.time.DayOfWeek.MONDAY)
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .toInstant()

            val startOfPreviousWeek = startOfThisWeek.minus(7, ChronoUnit.DAYS)

            val convertedThisWeek = converted.filter {
                val updatedAt = it.updatedAt
                updatedAt >= startOfThisWeek
            }

            val convertedPreviousWeek = converted.filter {
                val updatedAt = it.updatedAt
                updatedAt >= startOfPreviousWeek && updatedAt < startOfThisWeek
            }

            val convertedThisWeekCount = convertedThisWeek.size
            val convertedThisWeekValue = convertedThisWeek.sumOf { it.estimatedValue }

            val convertedPreviousWeekCount = convertedPreviousWeek.size
            val convertedPreviousWeekValue = convertedPreviousWeek.sumOf { it.estimatedValue }

            log.debug("[LEADS] Pipeline summary: studioId={}, inProgress={}, converted={}, abandoned={}",
                query.studioId.value, inProgress.size, converted.size, abandoned.size)

            PipelineSummaryResult(
                totalPipelineValue = totalPipelineValue,
                inProgressCount = inProgress.size,
                convertedCount = converted.size,
                abandonedCount = abandoned.size,
                convertedThisWeekCount = convertedThisWeekCount,
                convertedThisWeekValue = convertedThisWeekValue,
                convertedPreviousWeekCount = convertedPreviousWeekCount,
                convertedPreviousWeekValue = convertedPreviousWeekValue,
                leadsValueThisMonth = leadsValueThisMonth,
                convertedValueThisMonth = convertedValueThisMonth
            )
        }
}

data class PipelineSummaryResult(
    val totalPipelineValue: Long,
    val inProgressCount: Int,
    val convertedCount: Int,
    val abandonedCount: Int,
    val convertedThisWeekCount: Int,
    val convertedThisWeekValue: Long,
    val convertedPreviousWeekCount: Int,
    val convertedPreviousWeekValue: Long,
    val leadsValueThisMonth: Long,
    val convertedValueThisMonth: Long
)
