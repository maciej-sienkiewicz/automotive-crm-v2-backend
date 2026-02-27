package pl.detailing.crm.statistics

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
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

// ─── Controller ──────────────────────────────────────────────────────────────

@RestController
@RequestMapping("/api/v1/statistics")
class StatsController(
    private val getCategoryStatsHandler: GetCategoryStatsHandler,
    private val getServiceStatsHandler: GetServiceStatsHandler,
    private val getOverviewStatsHandler: GetOverviewStatsHandler,
    private val statsRepository: StatsRepository,
    private val serviceRepository: ServiceRepository
) {

    /**
     * Time-series statistics for a specific category.
     *
     * All service versions in the category's lineages are included,
     * ensuring historical accuracy even for archived services.
     *
     * Query params:
     * - granularity: DAILY | WEEKLY | MONTHLY | QUARTERLY | YEARLY (default: MONTHLY)
     * - startDate: ISO date YYYY-MM-DD (inclusive)
     * - endDate: ISO date YYYY-MM-DD (exclusive)
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
     *
     * Works with any version of the service — the handler always resolves the full lineage.
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
     * Also reports the count of active services not assigned to any category.
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
     * Useful for identifying "data leaks" that distort department-level reports.
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
            // endDate is inclusive for the user, so we move to the start of the next day
            LocalDate.parse(endDateStr).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        } catch (e: Exception) {
            throw ValidationException("Invalid endDate format. Expected YYYY-MM-DD, got: $endDateStr")
        }
        return start to end
    }

    private fun StatsDataPoint.toResponse() = StatsDataPointResponse(
        period = period.toString(),
        orderCount = orderCount,
        totalRevenueGross = totalRevenueGross
    )
}
