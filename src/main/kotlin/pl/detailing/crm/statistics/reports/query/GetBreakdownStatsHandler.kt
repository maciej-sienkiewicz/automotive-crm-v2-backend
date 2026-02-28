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
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceEntity
import pl.detailing.crm.statistics.category.manual.ManualServiceRegistry
import pl.detailing.crm.statistics.category.manual.ManualServiceRepository
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
     * UUID string for both catalog services and manual services (the stable UUID
     * from [pl.detailing.crm.statistics.category.manual.ManualServiceEntity]).
     * Never null — every service in the breakdown now has a real identifier.
     */
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
    private val serviceRepository: ServiceRepository,
    private val manualServiceRegistry: ManualServiceRegistry,
    private val manualServiceRepository: ManualServiceRepository,
    private val manualServiceCategoryAssignmentRepository: ManualServiceCategoryAssignmentRepository
) {

    /**
     * Single-query aggregation that drives the statistics view.
     *
     * Rules:
     * - Only COMPLETED visits are counted.
     * - All assigned catalog services appear even if orderCount = 0 for the given range.
     * - All assigned manual services appear even if orderCount = 0 for the given range.
     * - Unassigned manual services only appear if they have visits in the range.
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

        // ── 2. Catalog-service totals ─────────────────────────────────────────
        val assignedTotals: Map<UUID, Pair<Long, Long>> =
            statsRepository.getBreakdownAssignedServiceTotals(studioId.value, startDate, endDate)

        val unassignedTotals: Map<UUID, Pair<Long, Long>> =
            statsRepository.getBreakdownUnassignedServiceTotals(studioId.value, startDate, endDate)

        // ── 3. Manual service totals (grouped by name) ────────────────────────
        val manualTotalsByName: Map<String, Pair<Long, Long>> =
            statsRepository.getBreakdownManualServiceTotals(studioId.value, startDate, endDate)

        // Register any new manual service names encountered in this date range,
        // returning a name→entity map with stable UUIDs.
        val manualByName: Map<String, ManualServiceEntity> =
            manualServiceRegistry.findOrCreateAll(studioId.value, manualTotalsByName.keys)

        // ── 4. Manual service category assignments ────────────────────────────
        // All assignments (studio-wide) — used to split manual services into
        // "in category" vs "unassigned" and to zero-fill assigned-but-inactive ones.
        val allManualAssignments =
            manualServiceCategoryAssignmentRepository.findByStudioId(studioId.value)

        // Pre-load the ManualServiceEntity for every assignment so we have names
        // even for manual services with no visits in the current date range.
        val assignedManualIds = allManualAssignments.map { it.manualServiceId }.toSet()
        val assignedManualById: Map<UUID, ManualServiceEntity> =
            if (assignedManualIds.isEmpty()) emptyMap()
            else manualServiceRepository.findAllById(assignedManualIds).associateBy { it.id }

        val manualCatByManualId: Map<UUID, UUID> =  // manualServiceId → categoryId
            allManualAssignments.associate { it.manualServiceId to it.categoryId }

        // ── 5. Load JPA entities for catalog services ─────────────────────────
        val activeCategories = serviceCategoryRepository.findActiveByStudioId(studioId.value)
        val allStudioServices = serviceRepository.findByStudioId(studioId.value)

        val allServicesById = allStudioServices.associateBy { it.id }
        val replacedBy: Map<UUID, UUID> = allStudioServices
            .filter { it.replacesServiceId != null }
            .associate { it.replacesServiceId!! to it.id }

        val latestByRoot: Map<UUID, ServiceEntity> =
            allStudioServices
                .filter { it.replacesServiceId == null }
                .mapNotNull { root ->
                    val latest = resolveLatest(root.id, replacedBy, allServicesById)
                    latest?.let { root.id to it }
                }
                .toMap()

        // ── 6. Catalog category assignments ───────────────────────────────────
        val activeCategoryIds = activeCategories.map { it.id }.toSet()
        val assignmentRows = categoryServiceAssignmentRepository.findAllServiceIdsByStudio(studioId.value)
        val servicesByCategory: Map<UUID, List<UUID>> = assignmentRows
            .filter { it.categoryId in activeCategoryIds }
            .groupBy({ it.categoryId }, { it.serviceId })

        // ── 7. Assemble categories ────────────────────────────────────────────
        val categories: List<CategoryBreakdownItem> = activeCategories.map { cat ->

            // Catalog services assigned to this category
            val catalogServices: List<ServiceBreakdownItem> =
                (servicesByCategory[cat.id] ?: emptyList()).mapNotNull { rootId ->
                    val latest = latestByRoot[rootId] ?: return@mapNotNull null
                    val (orderCount, revenue) = assignedTotals[rootId] ?: (0L to 0L)
                    ServiceBreakdownItem(
                        serviceId = rootId.toString(),
                        serviceName = latest.name,
                        isActive = latest.isActive,
                        totals = BreakdownTotals(orderCount, revenue)
                    )
                }

            // Manual services assigned to this category (zero-filled if no visits in range)
            val manualServices: List<ServiceBreakdownItem> =
                allManualAssignments
                    .filter { it.categoryId == cat.id }
                    .mapNotNull { assignment ->
                        val manual = assignedManualById[assignment.manualServiceId]
                            ?: return@mapNotNull null
                        val (orderCount, revenue) =
                            manualTotalsByName[manual.serviceName] ?: (0L to 0L)
                        ServiceBreakdownItem(
                            serviceId = manual.id.toString(),
                            serviceName = manual.serviceName,
                            isActive = true,
                            totals = BreakdownTotals(orderCount, revenue)
                        )
                    }

            val services = (catalogServices + manualServices)
                .sortedByDescending { it.totals.totalRevenueGross }

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
        // a) Unassigned catalog services (only those with visits in range)
        val catalogUnassigned: List<ServiceBreakdownItem> =
            unassignedTotals.mapNotNull { (rootId, totals) ->
                val latest = latestByRoot[rootId] ?: return@mapNotNull null
                val (orderCount, revenue) = totals
                ServiceBreakdownItem(
                    serviceId = rootId.toString(),
                    serviceName = latest.name,
                    isActive = latest.isActive,
                    totals = BreakdownTotals(orderCount, revenue)
                )
            }

        // b) Unassigned manual services (only those with visits in range)
        val manualUnassigned: List<ServiceBreakdownItem> =
            manualByName.values
                .filter { it.id !in manualCatByManualId }
                .mapNotNull { manual ->
                    val (orderCount, revenue) =
                        manualTotalsByName[manual.serviceName] ?: return@mapNotNull null
                    ServiceBreakdownItem(
                        serviceId = manual.id.toString(),
                        serviceName = manual.serviceName,
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
