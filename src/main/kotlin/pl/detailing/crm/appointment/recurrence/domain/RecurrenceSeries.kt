package pl.detailing.crm.appointment.recurrence.domain

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@JvmInline
value class RecurrenceSeriesId(val value: UUID) {
    companion object {
        fun random() = RecurrenceSeriesId(UUID.randomUUID())
        fun fromString(s: String) = RecurrenceSeriesId(UUID.fromString(s))
    }
    override fun toString() = value.toString()
}

enum class RecurrenceType { WEEKLY, MONTHLY }

enum class RecurrenceEndType { DATE, COUNT, OPEN }

data class RecurrenceSeries(
    val id: RecurrenceSeriesId,
    val studioId: StudioId,
    val type: RecurrenceType,
    // WEEKLY fields
    val intervalWeeks: Int? = null,
    val daysOfWeek: Set<DayOfWeek>? = null,
    // MONTHLY fields
    val dayOfMonth: Int? = null,
    // End condition
    val endType: RecurrenceEndType,
    val endDate: java.time.LocalDate? = null,
    val maxOccurrences: Int? = null,
    val isOpenEnded: Boolean = false,
    val createdBy: UserId,
    val createdAt: Instant
) {
    companion object {
        const val MAX_OCCURRENCES_HARD_CAP = 104
        private val WARSAW = ZoneId.of("Europe/Warsaw")
    }

    fun generateOccurrenceDates(templateStart: Instant, templateEnd: Instant): List<Pair<Instant, Instant>> {
        val duration = templateEnd.epochSecond - templateStart.epochSecond
        val dates = mutableListOf<Pair<Instant, Instant>>()
        val cap = when (endType) {
            RecurrenceEndType.COUNT -> minOf(maxOccurrences ?: MAX_OCCURRENCES_HARD_CAP, MAX_OCCURRENCES_HARD_CAP)
            RecurrenceEndType.DATE -> MAX_OCCURRENCES_HARD_CAP
            RecurrenceEndType.OPEN -> MAX_OCCURRENCES_HARD_CAP
        }

        when (type) {
            RecurrenceType.WEEKLY -> generateWeekly(templateStart, duration, cap, dates)
            RecurrenceType.MONTHLY -> generateMonthly(templateStart, duration, cap, dates)
        }

        return dates
    }

    private fun generateWeekly(
        templateStart: Instant,
        durationSeconds: Long,
        cap: Int,
        out: MutableList<Pair<Instant, Instant>>
    ) {
        val weeks = intervalWeeks ?: 1
        val days = daysOfWeek ?: emptySet()
        val startZdt = templateStart.atZone(WARSAW)

        // Build ordered list of target days sorted by position relative to start day
        val orderedDays = DayOfWeek.values().toList().let { allDays ->
            val startDayIndex = startZdt.dayOfWeek.value - 1
            (allDays.drop(startDayIndex) + allDays.take(startDayIndex))
                .filter { it in days }
        }

        var weekBase = startZdt.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val endDateInstant = endDate?.atStartOfDay(WARSAW)?.plusDays(1)?.toInstant()

        outer@ while (out.size < cap) {
            for (day in orderedDays) {
                if (out.size >= cap) break@outer
                val candidate = weekBase.with(day)
                    .withHour(startZdt.hour)
                    .withMinute(startZdt.minute)
                    .withSecond(startZdt.second)
                    .withNano(0)
                // Skip past dates before template start
                if (candidate.toInstant().isBefore(templateStart)) continue
                if (endDateInstant != null && candidate.toInstant().isAfter(endDateInstant)) break@outer
                val start = candidate.toInstant()
                out.add(Pair(start, Instant.ofEpochSecond(start.epochSecond + durationSeconds)))
            }
            weekBase = weekBase.plusWeeks(weeks.toLong())
        }
    }

    private fun generateMonthly(
        templateStart: Instant,
        durationSeconds: Long,
        cap: Int,
        out: MutableList<Pair<Instant, Instant>>
    ) {
        val dom = dayOfMonth ?: 1
        val startZdt = templateStart.atZone(WARSAW)
        val endDateInstant = endDate?.atStartOfDay(WARSAW)?.plusDays(1)?.toInstant()

        var month = startZdt.withDayOfMonth(1)
        val safeDay = minOf(dom, 28)

        while (out.size < cap) {
            val candidate = month.withDayOfMonth(safeDay)
                .withHour(startZdt.hour)
                .withMinute(startZdt.minute)
                .withSecond(startZdt.second)
                .withNano(0)

            if (!candidate.toInstant().isBefore(templateStart)) {
                if (endDateInstant != null && candidate.toInstant().isAfter(endDateInstant)) break
                val start = candidate.toInstant()
                out.add(Pair(start, Instant.ofEpochSecond(start.epochSecond + durationSeconds)))
            }
            month = month.plusMonths(1)
        }
    }
}
