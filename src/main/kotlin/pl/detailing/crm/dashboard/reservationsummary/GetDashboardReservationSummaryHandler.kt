package pl.detailing.crm.dashboard.reservationsummary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

@Service
class GetDashboardReservationSummaryHandler(
    private val appointmentRepository: AppointmentRepository
) {
    private val warsawZone = ZoneId.of("Europe/Warsaw")

    suspend fun handle(command: GetDashboardReservationSummaryCommand): GetDashboardReservationSummaryResult =
        withContext(Dispatchers.IO) {
            val weeks = command.weeks.coerceIn(1, 104)
            val today = LocalDate.now(warsawZone)
            val currentWeekMonday = today.with(DayOfWeek.MONDAY)
            val startMonday = currentWeekMonday.minusWeeks((weeks - 1).toLong())

            val from = startMonday.atStartOfDay(warsawZone).toInstant()
            val to = currentWeekMonday.plusWeeks(1).atStartOfDay(warsawZone).toInstant()

            val appointments = appointmentRepository.findByStudioIdAndCreatedAtRange(
                studioId = command.studioId.value,
                from = from,
                to = to
            )

            val byWeek = appointments.groupBy { appointment ->
                appointment.createdAt.atZone(warsawZone).toLocalDate().with(DayOfWeek.MONDAY)
            }

            val countForWeek = { monday: LocalDate -> (byWeek[monday] ?: emptyList()).size.toLong() }

            val buckets = (0 until weeks).map { i ->
                val weekMonday = startMonday.plusWeeks(i.toLong())
                WeeklyReservationBucket(
                    weekStart = weekMonday.toString(),
                    count = countForWeek(weekMonday)
                )
            }

            val currentWeekCount = countForWeek(currentWeekMonday)
            val previousWeekCount = countForWeek(currentWeekMonday.minusWeeks(1))

            GetDashboardReservationSummaryResult(
                currentWeek = WeekReservations(count = currentWeekCount),
                previousWeek = WeekReservations(count = previousWeekCount),
                deltaPercentage = calculateDelta(currentWeekCount, previousWeekCount),
                buckets = buckets
            )
        }

    private fun calculateDelta(current: Long, previous: Long): Double {
        if (previous == 0L) return if (current > 0) 100.0 else 0.0
        return ((current - previous).toDouble() / previous.toDouble()) * 100.0
    }
}
