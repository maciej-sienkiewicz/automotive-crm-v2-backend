package pl.detailing.crm.dashboard.revenuesummary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class GetDashboardRevenueSummaryHandler(
    private val visitRepository: VisitRepository
) {
    private val warsawZone = ZoneId.of("Europe/Warsaw")

    suspend fun handle(command: GetDashboardRevenueSummaryCommand): GetDashboardRevenueSummaryResult =
        withContext(Dispatchers.IO) {
            val weeks = command.weeks.coerceIn(1, 104)
            val today = LocalDate.now(warsawZone)
            val currentWeekMonday = today.with(DayOfWeek.MONDAY)
            val startMonday = currentWeekMonday.minusWeeks((weeks - 1).toLong())

            val fromInstant = startMonday.atStartOfDay(warsawZone).toInstant()
            val toInstant = Instant.now()

            val visits = visitRepository.findCompletedByStudioIdAndDateRange(
                studioId = command.studioId.value,
                from = fromInstant,
                to = toInstant
            )

            // Group by the Monday of each visit's scheduled week
            val byWeek = visits.groupBy { visit ->
                visit.scheduledDate.atZone(warsawZone).toLocalDate().with(DayOfWeek.MONDAY)
            }

            val grossForWeek = { monday: LocalDate ->
                (byWeek[monday] ?: emptyList()).sumOf { visit ->
                    visit.serviceItems.sumOf { it.finalPriceGross }
                }
            }

            val buckets = (0 until weeks).map { i ->
                val weekMonday = startMonday.plusWeeks(i.toLong())
                WeeklyRevenueBucket(
                    weekStart = weekMonday.toString(),
                    grossAmount = grossForWeek(weekMonday),
                    currency = "PLN"
                )
            }

            val currentWeekGross = grossForWeek(currentWeekMonday)
            val previousWeekGross = grossForWeek(currentWeekMonday.minusWeeks(1))

            GetDashboardRevenueSummaryResult(
                currentWeek = WeekRevenue(grossAmount = currentWeekGross, currency = "PLN"),
                previousWeek = WeekRevenue(grossAmount = previousWeekGross, currency = "PLN"),
                deltaPercentage = calculateDelta(currentWeekGross, previousWeekGross),
                buckets = buckets
            )
        }

    private fun calculateDelta(current: Long, previous: Long): Double {
        if (previous == 0L) return if (current > 0) 100.0 else 0.0
        return ((current - previous).toDouble() / previous.toDouble()) * 100.0
    }
}
