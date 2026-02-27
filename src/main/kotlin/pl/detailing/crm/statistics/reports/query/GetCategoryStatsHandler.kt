package pl.detailing.crm.statistics.reports.query

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.domain.StatsDataPoint
import pl.detailing.crm.statistics.reports.infrastructure.StatsRepository
import java.time.Instant

data class CategoryStatsResult(
    val categoryId: String,
    val categoryName: String,
    val granularity: Granularity,
    val startDate: Instant,
    val endDate: Instant,
    val data: List<StatsDataPoint>,
    val totalOrderCount: Long,
    val totalRevenueGross: Long
)

@Service
class GetCategoryStatsHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val statsRepository: StatsRepository
) {
    suspend fun handle(
        categoryId: ServiceCategoryId,
        studioId: StudioId,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant
    ): CategoryStatsResult = withContext(Dispatchers.IO) {
        if (!startDate.isBefore(endDate)) {
            throw ValidationException("startDate must be before endDate")
        }

        val category = serviceCategoryRepository.findByIdAndStudioId(
            categoryId.value,
            studioId.value
        ) ?: throw EntityNotFoundException("Category $categoryId not found")

        val data = statsRepository.getCategoryStats(
            studioId = studioId.value,
            categoryId = categoryId.value,
            granularity = granularity,
            startDate = startDate,
            endDate = endDate
        )

        CategoryStatsResult(
            categoryId = category.id.toString(),
            categoryName = category.name,
            granularity = granularity,
            startDate = startDate,
            endDate = endDate,
            data = data,
            totalOrderCount = data.sumOf { it.orderCount },
            totalRevenueGross = data.sumOf { it.totalRevenueGross }
        )
    }
}
