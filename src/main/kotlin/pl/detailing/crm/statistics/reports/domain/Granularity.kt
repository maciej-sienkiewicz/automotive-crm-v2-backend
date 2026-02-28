package pl.detailing.crm.statistics.reports.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

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
     * The Instant represents the start of a time bucket as returned by PostgreSQL date_trunc().
     */
    fun formatPeriod(instant: Instant): String {
        val odt = instant.atOffset(ZoneOffset.UTC)
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
