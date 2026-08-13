package pl.detailing.crm.statistics.reports.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

/**
 * The business timezone every statistics bucket is expressed in.
 *
 * A "day" in the statistics module means a day as the studio experiences it, which is
 * the same convention the visit list, calendar and dashboard already use. Bucketing in
 * UTC instead would push work finished after 22:00 local time (23:00 in winter) onto
 * the previous day's bar, so the chart and the visit list would disagree about which
 * day a visit belongs to.
 */
val STATS_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

/**
 * The same zone as an IANA name for use in PostgreSQL `AT TIME ZONE` expressions.
 *
 * SECURITY NOTE: interpolated into SQL, but it is a compile-time constant — never user input.
 */
const val STATS_ZONE_SQL: String = "Europe/Warsaw"

/**
 * Time granularity for statistics aggregation.
 *
 * Maps directly to PostgreSQL date_trunc() granularity strings.
 * Values are whitelisted at the enum level — never interpolated from raw user input.
 *
 * @param sqlValue      PostgreSQL date_trunc() argument
 * @param intervalSql   PostgreSQL interval literal for generate_series()
 */
enum class Granularity(val sqlValue: String, val intervalSql: String) {
    DAILY("day", "1 day"),
    WEEKLY("week", "1 week"),
    MONTHLY("month", "1 month"),
    QUARTERLY("quarter", "3 months"),
    YEARLY("year", "1 year");

    /**
     * Formats a period Instant into the label string expected by the frontend chart axis.
     *
     * The Instant represents the start of a time bucket as returned by PostgreSQL date_trunc(),
     * i.e. local midnight in [STATS_ZONE] — so it must be rendered back in that same zone to
     * recover the label the bucket was built from.
     */
    fun formatPeriod(instant: Instant): String {
        val odt = instant.atZone(STATS_ZONE)
        return when (this) {
            DAILY -> odt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            WEEKLY -> {
                val weekFields = WeekFields.ISO
                val year = odt.get(weekFields.weekBasedYear())
                val week = odt.get(weekFields.weekOfWeekBasedYear())
                "${year}-W${week.toString().padStart(2, '0')}"
            }
            MONTHLY -> odt.format(DateTimeFormatter.ofPattern("yyyy-MM"))
            QUARTERLY -> {
                val quarter = (odt.monthValue - 1) / 3 + 1
                "${odt.year}-Q$quarter"
            }
            YEARLY -> odt.year.toString()
        }
    }
}
