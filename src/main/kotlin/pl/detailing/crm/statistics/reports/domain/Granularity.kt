package pl.detailing.crm.statistics.reports.domain

/**
 * Time granularity for statistics aggregation.
 *
 * Maps directly to PostgreSQL date_trunc() granularity strings.
 * Values are whitelisted at the enum level — never interpolated from raw user input.
 */
enum class Granularity(val sqlValue: String) {
    DAILY("day"),
    WEEKLY("week"),
    MONTHLY("month"),
    QUARTERLY("quarter"),
    YEARLY("year")
}
