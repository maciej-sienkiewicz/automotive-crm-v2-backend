package pl.detailing.crm.statistics.reports.query

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.infrastructure.PeriodVisitRow
import pl.detailing.crm.statistics.reports.infrastructure.StatsRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.UUID

// ─── Response types ───────────────────────────────────────────────────────────

data class PeriodVisitServiceResult(
    val serviceId: String,
    val serviceName: String,
    val priceGross: Long,
    val inCategory: Boolean?
)

data class PeriodVisitResult(
    val visitId: String,
    val visitDate: String,
    val clientName: String,
    val vehicleInfo: String?,
    val totalRevenueGross: Long,
    val totalRevenueGrossAll: Long,
    val services: List<PeriodVisitServiceResult>
)

data class PeriodDetailResult(
    val period: String,
    val granularity: Granularity,
    val orderCount: Int,
    val totalRevenueGross: Long,
    val totalRevenueGrossAll: Long,
    val categoryName: String?,
    val visits: List<PeriodVisitResult>
)

// ─── Handler ─────────────────────────────────────────────────────────────────

@Service
class GetPeriodVisitsHandler(
    private val statsRepository: StatsRepository,
    private val serviceCategoryRepository: ServiceCategoryRepository
) {

    private val polishDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("pl-PL"))

    suspend fun handle(
        studioId: StudioId,
        period: String,
        granularity: Granularity,
        categoryId: String?
    ): PeriodDetailResult = withContext(Dispatchers.IO) {

        val (startDate, endDate) = parsePeriodToRange(period, granularity)

        if (startDate.isAfter(Instant.now())) {
            throw ValidationException("Okres '$period' jest całkowicie w przyszłości")
        }

        // Validate and resolve category
        val resolvedCategoryId: UUID?
        val categoryName: String?
        if (categoryId != null) {
            val catUuid = try {
                UUID.fromString(categoryId)
            } catch (e: IllegalArgumentException) {
                throw EntityNotFoundException("Category '$categoryId' not found")
            }
            val category = serviceCategoryRepository.findById(catUuid).orElse(null)
                ?: throw EntityNotFoundException("Category '$categoryId' not found")
            if (category.studioId != studioId.value) {
                throw ForbiddenException("Access denied to category '$categoryId'")
            }
            if (!category.isActive) {
                throw EntityNotFoundException("Category '$categoryId' not found")
            }
            resolvedCategoryId = catUuid
            categoryName = category.name
        } else {
            resolvedCategoryId = null
            categoryName = null
        }

        // Fetch raw rows (one per visit × service_item)
        val rows: List<PeriodVisitRow> = if (resolvedCategoryId != null) {
            statsRepository.getPeriodVisitRowsWithCategory(
                studioId = studioId.value,
                categoryId = resolvedCategoryId,
                startDate = startDate,
                endDate = endDate
            )
        } else {
            statsRepository.getPeriodVisitRows(
                studioId = studioId.value,
                startDate = startDate,
                endDate = endDate
            )
        }

        // Group rows by visit, preserving DESC completion date order
        val visitMap = linkedMapOf<UUID, MutableList<PeriodVisitRow>>()
        for (row in rows) {
            visitMap.getOrPut(row.visitId) { mutableListOf() }.add(row)
        }

        val visits = visitMap.map { (visitId, visitRows) ->
            assembleVisit(visitId, visitRows, resolvedCategoryId != null, studioId.value)
        }

        val totalRevenueGross = visits.sumOf { it.totalRevenueGross }
        val totalRevenueGrossAll = visits.sumOf { it.totalRevenueGrossAll }

        PeriodDetailResult(
            period = period,
            granularity = granularity,
            orderCount = visits.size,
            totalRevenueGross = totalRevenueGross,
            totalRevenueGrossAll = totalRevenueGrossAll,
            categoryName = categoryName,
            visits = visits
        )
    }

    private fun assembleVisit(
        visitId: UUID,
        rows: List<PeriodVisitRow>,
        withCategory: Boolean,
        studioId: UUID
    ): PeriodVisitResult {
        val first = rows.first()

        val visitDate = first.actualCompletionDate
            .atZone(ZoneOffset.UTC)
            .format(polishDateFormatter)

        val clientName = when {
            first.customerFirstName != null || first.customerLastName != null ->
                "${first.customerFirstName ?: ""} ${first.customerLastName ?: ""}".trim()
            else -> "Klient usunięty"
        }

        val vehicleInfo = buildVehicleInfo(first.brandSnapshot, first.modelSnapshot, first.yearOfProductionSnapshot)

        // Build service list from rows that actually have a service (filter out the
        // sentinel NULL row that appears when a visit has no service items).
        val services = rows
            .filter { it.serviceName != null }
            .map { row ->
                val svcId = row.serviceId?.toString()
                    ?: UUID.nameUUIDFromBytes("$studioId:${row.serviceName}".toByteArray(Charsets.UTF_8)).toString()
                PeriodVisitServiceResult(
                    serviceId = svcId,
                    serviceName = row.serviceName!!,
                    priceGross = row.finalPriceGross ?: 0L,
                    inCategory = if (withCategory) row.inCategory else null
                )
            }
            .let { list ->
                if (withCategory) {
                    // inCategory=true items first (desc by price), then false items (desc by price)
                    val inCat = list.filter { it.inCategory == true }.sortedByDescending { it.priceGross }
                    val notInCat = list.filter { it.inCategory != true }.sortedByDescending { it.priceGross }
                    inCat + notInCat
                } else {
                    list.sortedByDescending { it.priceGross }
                }
            }

        val totalRevenueGrossAll = services.sumOf { it.priceGross }
        val totalRevenueGross = if (withCategory) {
            services.filter { it.inCategory == true }.sumOf { it.priceGross }
        } else {
            totalRevenueGrossAll
        }

        return PeriodVisitResult(
            visitId = visitId.toString(),
            visitDate = visitDate,
            clientName = clientName,
            vehicleInfo = vehicleInfo,
            totalRevenueGross = totalRevenueGross,
            totalRevenueGrossAll = totalRevenueGrossAll,
            services = services
        )
    }

    private fun buildVehicleInfo(brand: String, model: String, year: Int?): String? {
        val base = "$brand $model"
        return if (year != null) "$base ($year)" else base
    }

    // ─── Period parsing ───────────────────────────────────────────────────────

    private fun parsePeriodToRange(period: String, granularity: Granularity): Pair<Instant, Instant> {
        return when (granularity) {
            Granularity.DAILY -> parseDailyPeriod(period)
            Granularity.WEEKLY -> parseWeeklyPeriod(period)
            Granularity.MONTHLY -> parseMonthlyPeriod(period)
            Granularity.QUARTERLY -> parseQuarterlyPeriod(period)
            Granularity.YEARLY -> parseYearlyPeriod(period)
        }
    }

    private fun parseDailyPeriod(period: String): Pair<Instant, Instant> {
        val date = try {
            LocalDate.parse(period, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        } catch (e: DateTimeParseException) {
            throw ValidationException("Okres '$period' nie jest prawidłowym okresem DAILY (oczekiwano YYYY-MM-DD)")
        }
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        return start to date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private fun parseWeeklyPeriod(period: String): Pair<Instant, Instant> {
        val match = Regex("^(\\d{4})-W(\\d{2})$").matchEntire(period)
            ?: throw ValidationException("Okres '$period' nie jest prawidłowym okresem WEEKLY (oczekiwano YYYY-Www)")
        val year = match.groupValues[1].toInt()
        val week = match.groupValues[2].toInt()
        if (week < 1 || week > 53) {
            throw ValidationException("Okres '$period' nie jest prawidłowym okresem WEEKLY (tydzień musi być 01-53)")
        }
        val monday = try {
            LocalDate.now()
                .with(WeekFields.ISO.weekBasedYear(), year.toLong())
                .with(WeekFields.ISO.weekOfWeekBasedYear(), week.toLong())
                .with(DayOfWeek.MONDAY)
        } catch (e: Exception) {
            throw ValidationException("Okres '$period' nie jest prawidłowym okresem WEEKLY")
        }
        val start = monday.atStartOfDay(ZoneOffset.UTC).toInstant()
        return start to monday.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private fun parseMonthlyPeriod(period: String): Pair<Instant, Instant> {
        val match = Regex("^(\\d{4})-(\\d{2})$").matchEntire(period)
            ?: throw ValidationException("Okres '$period' nie jest prawidłowym okresem MONTHLY (oczekiwano YYYY-MM)")
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        if (month < 1 || month > 12) {
            throw ValidationException("Okres '$period' nie jest prawidłowym okresem MONTHLY (miesiąc musi być 01-12)")
        }
        val date = LocalDate.of(year, month, 1)
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        return start to date.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private fun parseQuarterlyPeriod(period: String): Pair<Instant, Instant> {
        val match = Regex("^(\\d{4})-Q([1-4])$").matchEntire(period)
            ?: throw ValidationException("Okres '$period' nie jest prawidłowym okresem QUARTERLY (oczekiwano YYYY-Qq)")
        val year = match.groupValues[1].toInt()
        val quarter = match.groupValues[2].toInt()
        val startMonth = (quarter - 1) * 3 + 1
        val date = LocalDate.of(year, startMonth, 1)
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        return start to date.plusMonths(3).atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private fun parseYearlyPeriod(period: String): Pair<Instant, Instant> {
        if (!Regex("^\\d{4}$").matches(period)) {
            throw ValidationException("Okres '$period' nie jest prawidłowym okresem YEARLY (oczekiwano YYYY)")
        }
        val year = period.toInt()
        val date = LocalDate.of(year, 1, 1)
        val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        return start to date.plusYears(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }
}
