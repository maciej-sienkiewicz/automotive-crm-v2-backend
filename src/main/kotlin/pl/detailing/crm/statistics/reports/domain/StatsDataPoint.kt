package pl.detailing.crm.statistics.reports.domain

import java.time.Instant

/**
 * A single aggregated data point in a time-series statistics result.
 *
 * @param period    Start of the time bucket (e.g. 2024-03-01T00:00:00Z for a MONTHLY bucket in March).
 * @param orderCount    Number of distinct visits (work orders) in this period.
 * @param totalRevenueGross  Sum of finalPriceGross from all matched visit service items, in cents.
 */
data class StatsDataPoint(
    val period: Instant,
    val orderCount: Long,
    val totalRevenueGross: Long
)
