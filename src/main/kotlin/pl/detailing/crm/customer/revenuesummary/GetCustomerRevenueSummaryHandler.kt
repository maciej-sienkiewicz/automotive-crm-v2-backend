package pl.detailing.crm.customer.revenuesummary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.YearMonth
import java.time.ZoneOffset

@Service
class GetCustomerRevenueSummaryHandler(
    private val visitRepository: VisitRepository
) {
    suspend fun handle(command: GetCustomerRevenueSummaryCommand): GetCustomerRevenueSummaryResult =
        withContext(Dispatchers.IO) {
            val months = command.months.coerceIn(1, 120)
            val currentMonth = YearMonth.now(ZoneOffset.UTC)
            val fromMonth = currentMonth.minusMonths((months - 1).toLong())

            val fromInstant = fromMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            val toInstant = currentMonth.atEndOfMonth().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

            val visits = visitRepository.findCompletedByCustomerIdAndDateRange(
                customerId = command.customerId.value,
                studioId = command.studioId.value,
                from = fromInstant,
                to = toInstant
            )

            // Group visits by YearMonth using their scheduledDate in UTC
            val byMonth = visits.groupBy { visit ->
                val localDate = visit.scheduledDate.atZone(ZoneOffset.UTC).toLocalDate()
                YearMonth.of(localDate.year, localDate.monthValue)
            }

            // Build exactly `months` buckets, filling zeros where there are no visits
            val buckets = (0 until months).map { i ->
                val ym = fromMonth.plusMonths(i.toLong())
                val monthVisits = byMonth[ym] ?: emptyList()
                val grossAmount = monthVisits.sumOf { visit ->
                    visit.serviceItems.sumOf { it.finalPriceGross }
                }
                RevenueBucket(
                    year = ym.year,
                    month = ym.monthValue,
                    grossAmount = grossAmount,
                    currency = "PLN",
                    visitCount = monthVisits.size
                )
            }

            val totalGross = visits.sumOf { visit ->
                visit.serviceItems.sumOf { it.finalPriceGross }
            }
            val totalNet = visits.sumOf { visit ->
                visit.serviceItems.sumOf { it.finalPriceNet }
            }

            GetCustomerRevenueSummaryResult(
                buckets = buckets,
                total = RevenueTotal(
                    grossAmount = totalGross,
                    netAmount = totalNet,
                    currency = "PLN",
                    visitCount = visits.size
                ),
                period = RevenuePeriod(
                    from = fromMonth.atDay(1).toString(),
                    to = currentMonth.atEndOfMonth().toString()
                )
            )
        }
}
