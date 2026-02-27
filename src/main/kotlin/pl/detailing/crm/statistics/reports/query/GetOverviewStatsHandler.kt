package pl.detailing.crm.statistics.reports.query

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.domain.StatsDataPoint
import pl.detailing.crm.statistics.reports.infrastructure.StatsRepository
import java.time.Instant

data class OverviewStatsResult(
    val granularity: Granularity,
    val startDate: Instant,
    val endDate: Instant,
    val data: List<StatsDataPoint>,
    val totalOrderCount: Long,
    val totalRevenueGross: Long,
    val unassignedServiceCount: Int
)

@Service
class GetOverviewStatsHandler(
    private val statsRepository: StatsRepository,
    private val serviceRepository: ServiceRepository
) {
    suspend fun handle(
        studioId: StudioId,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant
    ): OverviewStatsResult = withContext(Dispatchers.IO) {
        if (!startDate.isBefore(endDate)) {
            throw ValidationException("startDate must be before endDate")
        }

        val data = statsRepository.getOverviewStats(
            studioId = studioId.value,
            granularity = granularity,
            startDate = startDate,
            endDate = endDate
        )

        val unassignedIds = statsRepository.findUnassignedServiceIds(studioId.value)

        OverviewStatsResult(
            granularity = granularity,
            startDate = startDate,
            endDate = endDate,
            data = data,
            totalOrderCount = data.sumOf { it.orderCount },
            totalRevenueGross = data.sumOf { it.totalRevenueGross },
            unassignedServiceCount = unassignedIds.size
        )
    }
}
