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
    /**
     * UUID string for catalog services; null for manually-entered visit services
     * (visit_service_items rows where service_id IS NULL).
     */
    val serviceId: String?,
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
     * - Visit service items with service_id = NULL (manual/ad-hoc services) appear
     *   in unassignedServices with serviceId = null, grouped by service_name.
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

        // ── 3. Unassigned catalog-service totals (one batch SQL) ──────────────
        val unassignedTotals: Map<UUID, Pair<Long, Long>> =
            statsRepository.getBreakdownUnassignedServiceTotals(studioId.value, startDate, endDate)

        // ── 4. Manual service totals (null service_id, grouped by name) ───────
        val manualTotals: Map<String, Pair<Long, Long>> =
            statsRepository.getBreakdownManualServiceTotals(studioId.value, startDate, endDate)

        // ── 5. Load JPA entities ──────────────────────────────────────────────
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

        // ── 6. Build category assignments map ─────────────────────────────────
        // categoryId → list of root service IDs
        val activeCategoryIds = activeCategories.map { it.id }.toSet()
        val assignmentRows = categoryServiceAssignmentRepository.findAllServiceIdsByStudio(studioId.value)
        val servicesByCategory: Map<UUID, List<UUID>> = assignmentRows
            .filter { it.categoryId in activeCategoryIds }
            .groupBy({ it.categoryId }, { it.serviceId })

        // ── 7. Assemble categories ────────────────────────────────────────────
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

        // ── 8. Assemble unassigned services ───────────────────────────────────
        // a) Catalog services not in any category (with activity in range)
        val catalogUnassigned: List<ServiceBreakdownItem> = unassignedTotals.mapNotNull { (rootId, totals) ->
            val latest = latestByRoot[rootId] ?: return@mapNotNull null
            val (orderCount, revenue) = totals
            ServiceBreakdownItem(
                serviceId = rootId.toString(),
                serviceName = latest.name,
                isActive = latest.isActive,
                totals = BreakdownTotals(orderCount, revenue)
            )
        }

        // b) Manual services (service_id IS NULL in visit_service_items)
        //    Each distinct service_name is a separate entry; serviceId = null.
        val manualUnassigned: List<ServiceBreakdownItem> = manualTotals.map { (name, totals) ->
            val (orderCount, revenue) = totals
            ServiceBreakdownItem(
                serviceId = null,
                serviceName = name,
                isActive = true,
                totals = BreakdownTotals(orderCount, revenue)
            )
        }

        val unassignedServices = (catalogUnassigned + manualUnassigned)
            .sortedByDescending { it.totals.totalRevenueGross }

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
