package pl.detailing.crm.dashboard.revenuesummary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * Kafelek przychodu na Tablicy liczy MIESIĄCE, nie tygodnie.
 *
 * Tygodniowy horyzont okazał się szumem: krótki tydzień z jednym dniem wolnym
 * potrafił pokazać "-40% vs poprzedni tydzień" przy zdrowym biznesie, a
 * właściciel i tak rozlicza się miesięcznie (ZUS, VAT, wynagrodzenia).
 */
@Service
class GetDashboardRevenueSummaryHandler(
    private val visitRepository: VisitRepository
) {
    private val warsawZone = ZoneId.of("Europe/Warsaw")

    suspend fun handle(command: GetDashboardRevenueSummaryCommand): GetDashboardRevenueSummaryResult =
        withContext(Dispatchers.IO) {
            val months = command.months.coerceIn(1, 36)
            val currentMonthStart = LocalDate.now(warsawZone).withDayOfMonth(1)
            val startMonth = currentMonthStart.minusMonths((months - 1).toLong())

            val fromInstant = startMonth.atStartOfDay(warsawZone).toInstant()
            // Górna granica to początek następnego miesiąca (ekskluzywnie), żeby
            // wizyty zakończone dziś po południu też weszły do bieżącego miesiąca.
            val toInstant = currentMonthStart.plusMonths(1).atStartOfDay(warsawZone).toInstant()

            val visits = visitRepository.findCompletedByStudioIdAndDateRange(
                studioId = command.studioId.value,
                from = fromInstant,
                to = toInstant
            )

            val byMonth = visits.groupBy { visit ->
                visit.scheduledDate.atZone(warsawZone).toLocalDate().withDayOfMonth(1)
            }

            val grossForMonth = { monthStart: LocalDate ->
                (byMonth[monthStart] ?: emptyList()).sumOf { visit ->
                    visit.serviceItems.sumOf { it.finalPriceGross }
                }
            }

            val buckets = (0 until months).map { i ->
                val monthStart = startMonth.plusMonths(i.toLong())
                MonthlyRevenueBucket(
                    monthStart = monthStart.toString(),
                    grossAmount = grossForMonth(monthStart),
                    currency = "PLN"
                )
            }

            val currentGross = grossForMonth(currentMonthStart)
            val previousGross = grossForMonth(currentMonthStart.minusMonths(1))

            GetDashboardRevenueSummaryResult(
                currentMonth = MonthRevenue(grossAmount = currentGross, currency = "PLN"),
                previousMonth = MonthRevenue(grossAmount = previousGross, currency = "PLN"),
                deltaPercentage = calculateDelta(currentGross, previousGross),
                buckets = buckets
            )
        }

    private fun calculateDelta(current: Long, previous: Long): Double {
        if (previous == 0L) return if (current > 0) 100.0 else 0.0
        return ((current - previous).toDouble() / previous.toDouble()) * 100.0
    }
}
