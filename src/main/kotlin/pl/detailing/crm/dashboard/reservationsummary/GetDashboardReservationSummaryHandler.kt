package pl.detailing.crm.dashboard.reservationsummary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * Kafelek rezerwacji na Tablicy liczy MIESIĄCE, nie tygodnie — z tego samego
 * powodu co przychód: tygodniowa delta skakała o dziesiątki procent od jednego
 * długiego weekendu i nie dawała żadnej decyzji.
 */
@Service
class GetDashboardReservationSummaryHandler(
    private val appointmentRepository: AppointmentRepository
) {
    private val warsawZone = ZoneId.of("Europe/Warsaw")

    suspend fun handle(command: GetDashboardReservationSummaryCommand): GetDashboardReservationSummaryResult =
        withContext(Dispatchers.IO) {
            val months = command.months.coerceIn(1, 36)
            val currentMonthStart = LocalDate.now(warsawZone).withDayOfMonth(1)
            val startMonth = currentMonthStart.minusMonths((months - 1).toLong())

            val from = startMonth.atStartOfDay(warsawZone).toInstant()
            val to = currentMonthStart.plusMonths(1).atStartOfDay(warsawZone).toInstant()

            val appointments = appointmentRepository.findByStudioIdAndCreatedAtRange(
                studioId = command.studioId.value,
                from = from,
                to = to
            )

            val byMonth = appointments.groupBy { appointment ->
                appointment.createdAt.atZone(warsawZone).toLocalDate().withDayOfMonth(1)
            }

            val countForMonth = { monthStart: LocalDate -> (byMonth[monthStart] ?: emptyList()).size.toLong() }

            val buckets = (0 until months).map { i ->
                val monthStart = startMonth.plusMonths(i.toLong())
                MonthlyReservationBucket(
                    monthStart = monthStart.toString(),
                    count = countForMonth(monthStart)
                )
            }

            val currentCount = countForMonth(currentMonthStart)
            val previousCount = countForMonth(currentMonthStart.minusMonths(1))

            GetDashboardReservationSummaryResult(
                currentMonth = MonthReservations(count = currentCount),
                previousMonth = MonthReservations(count = previousCount),
                deltaPercentage = calculateDelta(currentCount, previousCount),
                buckets = buckets
            )
        }

    private fun calculateDelta(current: Long, previous: Long): Double {
        if (previous == 0L) return if (current > 0) 100.0 else 0.0
        return ((current - previous).toDouble() / previous.toDouble()) * 100.0
    }
}
