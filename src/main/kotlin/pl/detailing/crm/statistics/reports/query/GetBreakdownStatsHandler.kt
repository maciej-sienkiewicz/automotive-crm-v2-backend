package pl.detailing.crm.statistics.reports.query

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.domain.StatsDataPoint
import pl.detailing.crm.statistics.reports.infrastructure.StatsRepository
import java.time.Instant
import java.util.UUID

// ─── Result types ─────────────────────────────────────────────────────────────

data class BreakdownTotals(
    val orderCount: Long,
    val totalRevenueGross: Long
)

data class ServiceBreakdownItem(
    val serviceId: String,
    val serviceName: String,
    val isActive: Boolean,
    val totals: BreakdownTotals
)

data class CategoryBreakdownItem(
    val categoryId: String,
    val categoryName: String,
    val description: String?,
    val color: String?,
    val totals: BreakdownTotals,
    val services: List<ServiceBreakdownItem>
)

data class BreakdownResult(
    val granularity: Granularity,
    val startDate: String,
    val endDate: String,
    val overviewData: List<StatsDataPoint>,
    val overviewTotals: BreakdownTotals,
    val categories: List<CategoryBreakdownItem>,
    val unassignedServices: List<ServiceBreakdownItem>
)

// ─── Handler ──────────────────────────────────────────────────────────────────

@Service
class GetBreakdownStatsHandler(
    private val statsRepository: StatsRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository
) {

    /**
     * Single-query aggregation that drives the statistics view.
     *
     * Replaces: overview + unassigned-services + N×category-detail + M×service-stats.
     *
     * Rules:
     * - Only COMPLETED visits are counted.
     * - All assigned services appear even if orderCount = 0 for the given range.
     * - All periods in the range are present in overviewData (zero-filled).
     * - One service belongs to at most one category.
     */
    suspend fun handle(
        studioId: StudioId,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant,
        startDateStr: String,
        endDateStr: String
    ): BreakdownResult = withContext(Dispatchers.IO) {
        if (!startDate.isBefore(endDate)) {
            throw ValidationException("startDate must be before or equal to endDate")
        }

        val endDateInclusive = endDate.minusSeconds(1)

        // ── 1. Overview time-series (all periods filled) ──────────────────────
        val overviewData = statsRepository.getBreakdownOverview(
            studioId = studioId.value,
            granularity = granularity,
            startDate = startDate,
            endDate = endDate,
            endDateInclusive = endDateInclusive
        )
        val overviewTotals = BreakdownTotals(
            orderCount = overviewData.sumOf { it.orderCount },
            totalRevenueGross = overviewData.sumOf { it.totalRevenueGross }
        )

        // ── 2. Assigned-service totals (one batch SQL) ────────────────────────
        val assignedTotals: Map<UUID, Pair<Long, Long>> =
            statsRepository.getBreakdownAssignedServiceTotals(studioId.value, startDate, endDate)

        // ── 3. Unassigned-service totals (one batch SQL) ──────────────────────
        val unassignedTotals: Map<UUID, Pair<Long, Long>> =
            statsRepository.getBreakdownUnassignedServiceTotals(studioId.value, startDate, endDate)

        // ── 4. Load JPA entities ──────────────────────────────────────────────
        val activeCategories = serviceCategoryRepository.findActiveByStudioId(studioId.value)
        val allStudioServices = serviceRepository.findByStudioId(studioId.value)

        // Build service lookup maps
        val allServicesById = allStudioServices.associateBy { it.id }
        val replacedBy: Map<UUID, UUID> = allStudioServices
            .filter { it.replacesServiceId != null }
            .associate { it.replacesServiceId!! to it.id }

        // Map from root service ID → latest-version service entity
        val latestByRoot: Map<UUID, ServiceEntity> =
            allStudioServices
                .filter { it.replacesServiceId == null }   // roots only
                .mapNotNull { root ->
                    val latest = resolveLatest(root.id, replacedBy, allServicesById)
                    latest?.let { root.id to it }
                }
                .toMap()

        // ── 5. Build category assignments map ─────────────────────────────────
        // categoryId → list of root service IDs
        val activeCategoryIds = activeCategories.map { it.id }.toSet()
        val assignmentRows = categoryServiceAssignmentRepository.findAllServiceIdsByStudio(studioId.value)
        val servicesByCategory: Map<UUID, List<UUID>> = assignmentRows
            .filter { it.categoryId in activeCategoryIds }
            .groupBy({ it.categoryId }, { it.serviceId })

        // ── 6. Assemble categories ────────────────────────────────────────────
        val categories: List<CategoryBreakdownItem> = activeCategories.map { cat ->
            val assignedRootIds = servicesByCategory[cat.id] ?: emptyList()

            val services: List<ServiceBreakdownItem> = assignedRootIds.mapNotNull { rootId ->
                val latest = latestByRoot[rootId] ?: return@mapNotNull null
                val (orderCount, revenue) = assignedTotals[rootId] ?: (0L to 0L)
                ServiceBreakdownItem(
                    serviceId = rootId.toString(),
                    serviceName = latest.name,
                    isActive = latest.isActive,
                    totals = BreakdownTotals(orderCount, revenue)
                )
            }.sortedByDescending { it.totals.totalRevenueGross }

            val catTotals = BreakdownTotals(
                orderCount = services.sumOf { it.totals.orderCount },
                totalRevenueGross = services.sumOf { it.totals.totalRevenueGross }
            )

            CategoryBreakdownItem(
                categoryId = cat.id.toString(),
                categoryName = cat.name,
                description = cat.description,
                color = cat.color,
                totals = catTotals,
                services = services
            )
        }.sortedByDescending { it.totals.totalRevenueGross }

        // ── 7. Assemble unassigned services ───────────────────────────────────
        // Include all root services not assigned to any active category that have
        // activity in the date range (returned from getBreakdownUnassignedServiceTotals).
        val unassignedServices: List<ServiceBreakdownItem> = unassignedTotals.mapNotNull { (rootId, totals) ->
            val latest = latestByRoot[rootId] ?: return@mapNotNull null
            val (orderCount, revenue) = totals
            ServiceBreakdownItem(
                serviceId = rootId.toString(),
                serviceName = latest.name,
                isActive = latest.isActive,
                totals = BreakdownTotals(orderCount, revenue)
            )
        }.sortedByDescending { it.totals.totalRevenueGross }

        BreakdownResult(
            granularity = granularity,
            startDate = startDateStr,
            endDate = endDateStr,
            overviewData = overviewData,
            overviewTotals = overviewTotals,
            categories = categories,
            unassignedServices = unassignedServices
        )
    }

    /**
     * Traverses the replacedBy chain forward from a root to find the latest service version.
     */
    private fun resolveLatest(
        rootId: UUID,
        replacedBy: Map<UUID, UUID>,
        allServicesById: Map<UUID, ServiceEntity>
    ): ServiceEntity? {
        var currentId = rootId
        while (true) {
            val next = replacedBy[currentId]
            if (next == null) return allServicesById[currentId]
            currentId = next
        }
    }
}
