package pl.detailing.crm.statistics.reports.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.domain.StatsDataPoint
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Native SQL repository for aggregated time-series statistics.
 *
 * Uses PostgreSQL recursive CTEs to resolve complete service version chains,
 * ensuring historical accuracy: visits referencing archived/versioned services
 * are correctly counted under the originating service lineage.
 *
 * SECURITY NOTE: The only non-parameterized value is granularity.sqlValue, which is
 * sourced exclusively from the Granularity enum — never from raw user input.
 */
@Repository
class StatsRepository(
    private val jdbcTemplate: JdbcTemplate
) {

    /**
     * Returns time-series statistics for all services assigned to a given category.
     *
     * Algorithm:
     * 1. Seed CTE with root service IDs stored in category_service_assignments.
     * 2. Recursively find all newer versions (services where replaces_service_id is in the seed).
     * 3. Aggregate visit_service_items where service_id is in the full family.
     * 4. Exclude REJECTED and ARCHIVED visits.
     */
    fun getCategoryStats(
        studioId: UUID,
        categoryId: UUID,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant
    ): List<StatsDataPoint> {
        val sql = """
            WITH RECURSIVE service_family AS (
                -- Seed: root service IDs assigned to this category
                SELECT csa.service_id AS id
                FROM category_service_assignments csa
                WHERE csa.category_id = ?
                  AND csa.studio_id = ?

                UNION ALL

                -- Recursive: newer versions that replaced any member of the family
                SELECT s.id
                FROM services s
                INNER JOIN service_family sf ON s.replaces_service_id = sf.id
                WHERE s.studio_id = ?
            )
            SELECT
                date_trunc('${granularity.sqlValue}', v.scheduled_date)  AS period,
                COUNT(DISTINCT v.id)                                      AS order_count,
                COALESCE(SUM(vsi.final_price_gross), 0)                  AS total_revenue_gross
            FROM visit_service_items vsi
            INNER JOIN visits v ON vsi.visit_id = v.id
            WHERE v.studio_id = ?
              AND vsi.service_id IN (SELECT id FROM service_family)
              AND v.status NOT IN ('REJECTED', 'ARCHIVED')
              AND v.scheduled_date >= ?
              AND v.scheduled_date < ?
            GROUP BY date_trunc('${granularity.sqlValue}', v.scheduled_date)
            ORDER BY period ASC
        """.trimIndent()

        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                StatsDataPoint(
                    period = rs.getTimestamp("period").toInstant(),
                    orderCount = rs.getLong("order_count"),
                    totalRevenueGross = rs.getLong("total_revenue_gross")
                )
            },
            categoryId,
            studioId,
            studioId,           // filter in recursive join (prevents cross-tenant data)
            studioId,           // outer WHERE v.studio_id
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
    }

    /**
     * Returns time-series statistics for a single service lineage (all versions).
     *
     * The provided serviceId is treated as the ROOT of the version chain.
     * Callers are responsible for resolving the root before calling this method.
     */
    fun getServiceStats(
        studioId: UUID,
        rootServiceId: UUID,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant
    ): List<StatsDataPoint> {
        val sql = """
            WITH RECURSIVE service_family AS (
                -- Seed: the root service
                SELECT id
                FROM services
                WHERE id = ?
                  AND studio_id = ?

                UNION ALL

                -- Recursive: all newer versions
                SELECT s.id
                FROM services s
                INNER JOIN service_family sf ON s.replaces_service_id = sf.id
                WHERE s.studio_id = ?
            )
            SELECT
                date_trunc('${granularity.sqlValue}', v.scheduled_date)  AS period,
                COUNT(DISTINCT v.id)                                      AS order_count,
                COALESCE(SUM(vsi.final_price_gross), 0)                  AS total_revenue_gross
            FROM visit_service_items vsi
            INNER JOIN visits v ON vsi.visit_id = v.id
            WHERE v.studio_id = ?
              AND vsi.service_id IN (SELECT id FROM service_family)
              AND v.status NOT IN ('REJECTED', 'ARCHIVED')
              AND v.scheduled_date >= ?
              AND v.scheduled_date < ?
            GROUP BY date_trunc('${granularity.sqlValue}', v.scheduled_date)
            ORDER BY period ASC
        """.trimIndent()

        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                StatsDataPoint(
                    period = rs.getTimestamp("period").toInstant(),
                    orderCount = rs.getLong("order_count"),
                    totalRevenueGross = rs.getLong("total_revenue_gross")
                )
            },
            rootServiceId,
            studioId,
            studioId,           // filter in recursive join
            studioId,           // outer WHERE v.studio_id
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
    }

    /**
     * Returns time-series statistics for ALL visits in the studio, regardless of category.
     * Useful for overview/dashboard time-series comparison.
     */
    fun getOverviewStats(
        studioId: UUID,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant
    ): List<StatsDataPoint> {
        val sql = """
            SELECT
                date_trunc('${granularity.sqlValue}', v.scheduled_date)  AS period,
                COUNT(DISTINCT v.id)                                      AS order_count,
                COALESCE(SUM(vsi.final_price_gross), 0)                  AS total_revenue_gross
            FROM visit_service_items vsi
            INNER JOIN visits v ON vsi.visit_id = v.id
            WHERE v.studio_id = ?
              AND v.status NOT IN ('REJECTED', 'ARCHIVED')
              AND v.scheduled_date >= ?
              AND v.scheduled_date < ?
            GROUP BY date_trunc('${granularity.sqlValue}', v.scheduled_date)
            ORDER BY period ASC
        """.trimIndent()

        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                StatsDataPoint(
                    period = rs.getTimestamp("period").toInstant(),
                    orderCount = rs.getLong("order_count"),
                    totalRevenueGross = rs.getLong("total_revenue_gross")
                )
            },
            studioId,
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
    }

    /**
     * Returns active services that are NOT assigned to any category in this studio.
     * Helps identify "data leaks" — services being sold but not tracked in any department.
     */
    fun findUnassignedServiceIds(studioId: UUID): List<UUID> {
        val sql = """
            SELECT s.id
            FROM services s
            WHERE s.studio_id = ?
              AND s.is_active = true
              AND s.id NOT IN (
                  -- Exclude entire lineages that have any version assigned to a category.
                  -- We do this by resolving all roots and their descendants.
                  WITH RECURSIVE assigned_family AS (
                      SELECT csa.service_id AS id
                      FROM category_service_assignments csa
                      WHERE csa.studio_id = ?

                      UNION ALL

                      SELECT sv.id
                      FROM services sv
                      INNER JOIN assigned_family af ON sv.replaces_service_id = af.id
                      WHERE sv.studio_id = ?
                  )
                  SELECT id FROM assigned_family
              )
            ORDER BY s.name ASC
        """.trimIndent()

        return jdbcTemplate.queryForList(sql, UUID::class.java, studioId, studioId, studioId)
    }
}
