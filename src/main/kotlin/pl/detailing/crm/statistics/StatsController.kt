package pl.detailing.crm.statistics

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.domain.StatsDataPoint
import pl.detailing.crm.statistics.reports.infrastructure.StatsRepository
import pl.detailing.crm.statistics.reports.query.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ─── Response bodies ─────────────────────────────────────────────────────────

data class StatsDataPointResponse(
    val period: String,
    val orderCount: Long,
    val totalRevenueGross: Long
)

data class CategoryStatsResponse(
    val categoryId: String,
    val categoryName: String,
    val granularity: String,
    val startDate: String,
    val endDate: String,
    val data: List<StatsDataPointResponse>,
    val totals: StatsTotals
)

data class ServiceStatsResponse(
    val serviceId: String,
    val serviceName: String,
    val isActive: Boolean,
    val granularity: String,
    val startDate: String,
    val endDate: String,
    val data: List<StatsDataPointResponse>,
    val totals: StatsTotals
)

data class OverviewStatsResponse(
    val granularity: String,
    val startDate: String,
    val endDate: String,
    val data: List<StatsDataPointResponse>,
    val totals: StatsTotals,
    val unassignedServiceCount: Int
)

data class StatsTotals(
    val orderCount: Long,
    val totalRevenueGross: Long
)

data class UnassignedServicesResponse(
    val services: List<UnassignedServiceItem>
)

data class UnassignedServiceItem(
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int
)

// ─── Breakdown response bodies ────────────────────────────────────────────────

data class BreakdownTotalsResponse(
    val orderCount: Long,
    val totalRevenueGross: Long
)

data class ServiceBreakdownItemResponse(
    val serviceId: String,
    val serviceName: String,
    val isActive: Boolean,
    val totals: BreakdownTotalsResponse
)

data class CategoryBreakdownItemResponse(
    val categoryId: String,
    val categoryName: String,
    val description: String?,
    val color: String?,
    val totals: BreakdownTotalsResponse,
    val services: List<ServiceBreakdownItemResponse>
)

data class BreakdownResponse(
    val period: BreakdownPeriod,
    val overview: BreakdownOverview,
    val categories: List<CategoryBreakdownItemResponse>,
    val unassignedServices: List<ServiceBreakdownItemResponse>
)

data class BreakdownPeriod(
    val granularity: String,
    val startDate: String,
    val endDate: String
)

data class BreakdownOverview(
    val data: List<StatsDataPointResponse>,
    val totals: BreakdownTotalsResponse
)

// ─── Controller ──────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/statistics")
class StatsController(
    private val getCategoryStatsHandler: GetCategoryStatsHandler,
    private val getServiceStatsHandler: GetServiceStatsHandler,
    private val getOverviewStatsHandler: GetOverviewStatsHandler,
    private val getBreakdownStatsHandler: GetBreakdownStatsHandler,
    private val statsRepository: StatsRepository,
    private val serviceRepository: ServiceRepository
) {

    /**
     * Aggregated breakdown for the statistics view.
     *
     * Single endpoint replacing: overview + unassigned-services +
     * N×category-detail + M×service-stats.
     *
     * Only COMPLETED visits are counted.
     * All periods in the range are present in overview.data (zero-filled).
     *
     * Query params:
     * - granularity: DAILY | WEEKLY | MONTHLY | QUARTERLY | YEARLY
     * - startDate: YYYY-MM-DD (inclusive)
     * - endDate: YYYY-MM-DD (inclusive)
     */
    @GetMapping("/breakdown")
    fun getBreakdown(
        @RequestParam granularity: String,
        @RequestParam startDate: String,
        @RequestParam endDate: String
    ): ResponseEntity<BreakdownResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val gran = parseGranularity(granularity)
        val (start, end) = parseDateRange(startDate, endDate)

        validateBreakdownDateRange(startDate, endDate, start, end)

        val result = getBreakdownStatsHandler.handle(
            studioId = principal.studioId,
            granularity = gran,
            startDate = start,
            endDate = end,
            startDateStr = startDate,
            endDateStr = endDate
        )

        ResponseEntity.ok(
            BreakdownResponse(
                period = BreakdownPeriod(
                    granularity = result.granularity.name,
                    startDate = result.startDate,
                    endDate = result.endDate
                ),
                overview = BreakdownOverview(
                    data = result.overviewData.map { it.toFormattedResponse(gran) },
                    totals = BreakdownTotalsResponse(
                        orderCount = result.overviewTotals.orderCount,
                        totalRevenueGross = result.overviewTotals.totalRevenueGross
                    )
                ),
                categories = result.categories.map { cat ->
                    CategoryBreakdownItemResponse(
                        categoryId = cat.categoryId,
                        categoryName = cat.categoryName,
                        description = cat.description,
                        color = cat.color,
                        totals = BreakdownTotalsResponse(
                            orderCount = cat.totals.orderCount,
                            totalRevenueGross = cat.totals.totalRevenueGross
                        ),
                        services = cat.services.map { svc ->
                            ServiceBreakdownItemResponse(
                                serviceId = svc.serviceId,
                                serviceName = svc.serviceName,
                                isActive = svc.isActive,
                                totals = BreakdownTotalsResponse(
                                    orderCount = svc.totals.orderCount,
                                    totalRevenueGross = svc.totals.totalRevenueGross
                                )
                            )
                        }
                    )
                },
                unassignedServices = result.unassignedServices.map { svc ->
                    ServiceBreakdownItemResponse(
                        serviceId = svc.serviceId,
                        serviceName = svc.serviceName,
                        isActive = svc.isActive,
                        totals = BreakdownTotalsResponse(
                            orderCount = svc.totals.orderCount,
                            totalRevenueGross = svc.totals.totalRevenueGross
                        )
                    )
                }
            )
        )
    }

    /**
     * Time-series statistics for a specific category.
     * Used when the user clicks a category to display its chart.
     */
    @GetMapping("/categories/{categoryId}")
    fun getCategoryStats(
        @PathVariable categoryId: String,
        @RequestParam(defaultValue = "MONTHLY") granularity: String,
        @RequestParam startDate: String,
        @RequestParam endDate: String
    ): ResponseEntity<CategoryStatsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val gran = parseGranularity(granularity)
        val (start, end) = parseDateRange(startDate, endDate)

        val result = getCategoryStatsHandler.handle(
            categoryId = ServiceCategoryId.fromString(categoryId),
            studioId = principal.studioId,
            granularity = gran,
            startDate = start,
            endDate = end
        )

        ResponseEntity.ok(
            CategoryStatsResponse(
                categoryId = result.categoryId,
                categoryName = result.categoryName,
                granularity = result.granularity.name,
                startDate = startDate,
                endDate = endDate,
                data = result.data.map { it.toResponse() },
                totals = StatsTotals(result.totalOrderCount, result.totalRevenueGross)
            )
        )
    }

    /**
     * Time-series statistics for a single service lineage (all versions).
     */
    @GetMapping("/services/{serviceId}")
    fun getServiceStats(
        @PathVariable serviceId: String,
        @RequestParam(defaultValue = "MONTHLY") granularity: String,
        @RequestParam startDate: String,
        @RequestParam endDate: String
    ): ResponseEntity<ServiceStatsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val gran = parseGranularity(granularity)
        val (start, end) = parseDateRange(startDate, endDate)

        val result = getServiceStatsHandler.handle(
            serviceId = ServiceId.fromString(serviceId),
            studioId = principal.studioId,
            granularity = gran,
            startDate = start,
            endDate = end
        )

        ResponseEntity.ok(
            ServiceStatsResponse(
                serviceId = result.serviceId,
                serviceName = result.serviceName,
                isActive = result.isActive,
                granularity = result.granularity.name,
                startDate = startDate,
                endDate = endDate,
                data = result.data.map { it.toResponse() },
                totals = StatsTotals(result.totalOrderCount, result.totalRevenueGross)
            )
        )
    }

    /**
     * Studio-wide time-series overview — aggregates all visits regardless of category.
     */
    @GetMapping("/overview")
    fun getOverviewStats(
        @RequestParam(defaultValue = "MONTHLY") granularity: String,
        @RequestParam startDate: String,
        @RequestParam endDate: String
    ): ResponseEntity<OverviewStatsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val gran = parseGranularity(granularity)
        val (start, end) = parseDateRange(startDate, endDate)

        val result = getOverviewStatsHandler.handle(
            studioId = principal.studioId,
            granularity = gran,
            startDate = start,
            endDate = end
        )

        ResponseEntity.ok(
            OverviewStatsResponse(
                granularity = result.granularity.name,
                startDate = startDate,
                endDate = endDate,
                data = result.data.map { it.toResponse() },
                totals = StatsTotals(result.totalOrderCount, result.totalRevenueGross),
                unassignedServiceCount = result.unassignedServiceCount
            )
        )
    }

    /**
     * Lists active services that are not assigned to any category.
     */
    @GetMapping("/unassigned-services")
    fun getUnassignedServices(): ResponseEntity<UnassignedServicesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val unassignedIds = statsRepository.findUnassignedServiceIds(principal.studioId.value)

        val services = unassignedIds.mapNotNull { id ->
            serviceRepository.findByIdAndStudioId(id, principal.studioId.value)
        }.map { entity ->
            UnassignedServiceItem(
                serviceId = entity.id.toString(),
                serviceName = entity.name,
                basePriceNet = entity.basePriceNet,
                vatRate = entity.vatRate
            )
        }

        ResponseEntity.ok(UnassignedServicesResponse(services = services))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun parseGranularity(value: String): Granularity {
        return try {
            Granularity.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ValidationException(
                "Invalid granularity '$value'. Allowed values: ${Granularity.entries.joinToString()}"
            )
        }
    }

    private fun parseDateRange(startDateStr: String, endDateStr: String): Pair<Instant, Instant> {
        val start = try {
            LocalDate.parse(startDateStr).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (e: Exception) {
            throw ValidationException("Invalid startDate format. Expected YYYY-MM-DD, got: $startDateStr")
        }
        val end = try {
            // endDate is inclusive for the user, so we advance to start of next day
            LocalDate.parse(endDateStr).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (e: Exception) {
            throw ValidationException("Invalid endDate format. Expected YYYY-MM-DD, got: $endDateStr")
        }
        return start to end
    }

    private fun validateBreakdownDateRange(
        startDateStr: String,
        endDateStr: String,
        start: Instant,
        end: Instant
    ) {
        if (!start.isBefore(end)) {
            throw ValidationException("startDate must be before or equal to endDate")
        }
        val tomorrow = Instant.now().plusSeconds(86_400)
        if (end.isAfter(tomorrow)) {
            throw ValidationException("endDate cannot be more than 1 day in the future")
        }
        val maxRange = 1826L * 86_400
        if (end.epochSecond - start.epochSecond > maxRange) {
            throw ValidationException("Date range cannot exceed 5 years (1826 days)")
        }
    }

    /** Formats the period Instant using the granularity's label format (e.g. "2024-03" for MONTHLY). */
    private fun StatsDataPoint.toFormattedResponse(gran: Granularity) = StatsDataPointResponse(
        period = gran.formatPeriod(period),
        orderCount = orderCount,
        totalRevenueGross = totalRevenueGross
    )

    /** Legacy helper — keeps period as raw ISO timestamp for existing endpoints. */
    private fun StatsDataPoint.toResponse() = StatsDataPointResponse(
        period = period.toString(),
        orderCount = orderCount,
        totalRevenueGross = totalRevenueGross
    )
}
