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
 * SECURITY NOTE: The only non-parameterized values are granularity.sqlValue and
 * granularity.intervalSql, which are sourced exclusively from the Granularity enum —
 * never from raw user input.
 */
@Repository
class StatsRepository(
    private val jdbcTemplate: JdbcTemplate
) {

    /**
     * Returns time-series statistics for all services assigned to a given category.
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
            studioId,
            studioId,
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
            studioId,
            studioId,
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
    }

    /**
     * Returns time-series statistics for ALL visits in the studio, regardless of category.
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
     */
    fun findUnassignedServiceIds(studioId: UUID): List<UUID> {
        val sql = """
            SELECT s.id
            FROM services s
            WHERE s.studio_id = ?
              AND s.is_active = true
              AND s.id NOT IN (
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

    // ─── Breakdown endpoint queries ───────────────────────────────────────────

    /**
     * Returns time-series overview for the entire studio with ALL periods filled (no gaps).
     *
     * Only COMPLETED visits are counted per the breakdown contract.
     *
     * @param startDate  inclusive start (start of day, UTC)
     * @param endDate    exclusive end (start of the day AFTER the last day, UTC)
     * @param endDateInclusive  last day's start (= endDate - 1 second), used to cap generate_series
     */
    fun getBreakdownOverview(
        studioId: UUID,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant,
        endDateInclusive: Instant
    ): List<StatsDataPoint> {
        val sql = """
            WITH date_series AS (
                SELECT generate_series(
                    date_trunc('${granularity.sqlValue}', ?::timestamptz),
                    date_trunc('${granularity.sqlValue}', ?::timestamptz),
                    '${granularity.intervalSql}'::interval
                ) AS period
            ),
            raw_stats AS (
                SELECT
                    date_trunc('${granularity.sqlValue}', v.scheduled_date) AS period,
                    COUNT(DISTINCT v.id)                                     AS order_count,
                    COALESCE(SUM(vsi.final_price_gross), 0)                 AS total_revenue_gross
                FROM visit_service_items vsi
                INNER JOIN visits v ON vsi.visit_id = v.id
                WHERE v.studio_id = ?
                  AND v.status = 'COMPLETED'
                  AND v.scheduled_date >= ?
                  AND v.scheduled_date < ?
                GROUP BY date_trunc('${granularity.sqlValue}', v.scheduled_date)
            )
            SELECT
                ds.period,
                COALESCE(rs.order_count, 0)           AS order_count,
                COALESCE(rs.total_revenue_gross, 0)   AS total_revenue_gross
            FROM date_series ds
            LEFT JOIN raw_stats rs ON ds.period = rs.period
            ORDER BY ds.period ASC
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
            Timestamp.from(startDate),       // generate_series start
            Timestamp.from(endDateInclusive), // generate_series end (inclusive last period)
            studioId,
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
    }

    /**
     * Returns aggregated totals per root-service-ID for all services assigned to any category.
     *
     * Uses a recursive CTE to expand each root service assignment into its full version lineage,
     * so all historical visits (including those referencing old service versions) are counted.
     * Only COMPLETED visits are included.
     *
     * Result: map of rootServiceId → (orderCount, totalRevenueGross).
     * Services with no visits in the range are NOT included (callers handle the zero-fill).
     */
    fun getBreakdownAssignedServiceTotals(
        studioId: UUID,
        startDate: Instant,
        endDate: Instant
    ): Map<UUID, Pair<Long, Long>> {
        val sql = """
            WITH RECURSIVE service_family AS (
                -- Seed: all root service IDs assigned to any category in this studio
                SELECT service_id AS root_id, service_id AS member_id
                FROM category_service_assignments
                WHERE studio_id = ?

                UNION ALL

                -- Recursive: newer versions of each assigned root
                SELECT sf.root_id, s.id AS member_id
                FROM services s
                INNER JOIN service_family sf ON s.replaces_service_id = sf.member_id
                WHERE s.studio_id = ?
            )
            SELECT
                sf.root_id                              AS service_id,
                COUNT(DISTINCT v.id)                    AS order_count,
                COALESCE(SUM(vsi.final_price_gross), 0) AS total_revenue_gross
            FROM visit_service_items vsi
            INNER JOIN visits v ON vsi.visit_id = v.id
            INNER JOIN service_family sf ON sf.member_id = vsi.service_id
            WHERE v.studio_id = ?
              AND v.status = 'COMPLETED'
              AND v.scheduled_date >= ?
              AND v.scheduled_date < ?
            GROUP BY sf.root_id
        """.trimIndent()

        val result = mutableMapOf<UUID, Pair<Long, Long>>()
        jdbcTemplate.query(
            sql,
            { rs ->
                val id = rs.getObject("service_id", UUID::class.java)
                val orderCount = rs.getLong("order_count")
                val revenue = rs.getLong("total_revenue_gross")
                result[id] = orderCount to revenue
            },
            studioId,
            studioId,
            studioId,
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
        return result
    }

    /**
     * Returns aggregated totals per root-service-ID for all service lineages NOT assigned
     * to any category (unassigned services).
     *
     * Includes ALL root service lineages regardless of isActive status, so services that were
     * sold and later deactivated still appear in historical stats.
     *
     * Services with no visits in the range are NOT included.
     */
    fun getBreakdownUnassignedServiceTotals(
        studioId: UUID,
        startDate: Instant,
        endDate: Instant
    ): Map<UUID, Pair<Long, Long>> {
        val sql = """
            WITH RECURSIVE
            -- Expand all assigned roots into their full lineages
            assigned_family AS (
                SELECT service_id AS id
                FROM category_service_assignments
                WHERE studio_id = ?

                UNION ALL

                SELECT s.id
                FROM services s
                INNER JOIN assigned_family af ON s.replaces_service_id = af.id
                WHERE s.studio_id = ?
            ),
            -- All root services not assigned to any category
            unassigned_roots AS (
                SELECT s.id AS root_id
                FROM services s
                WHERE s.studio_id = ?
                  AND s.replaces_service_id IS NULL
                  AND s.id NOT IN (SELECT id FROM assigned_family)
            ),
            -- Expand unassigned roots into their full lineages
            unassigned_family AS (
                SELECT root_id, root_id AS member_id
                FROM unassigned_roots

                UNION ALL

                SELECT uf.root_id, s.id AS member_id
                FROM services s
                INNER JOIN unassigned_family uf ON s.replaces_service_id = uf.member_id
                WHERE s.studio_id = ?
            )
            SELECT
                uf.root_id                              AS service_id,
                COUNT(DISTINCT v.id)                    AS order_count,
                COALESCE(SUM(vsi.final_price_gross), 0) AS total_revenue_gross
            FROM visit_service_items vsi
            INNER JOIN visits v ON vsi.visit_id = v.id
            INNER JOIN unassigned_family uf ON uf.member_id = vsi.service_id
            WHERE v.studio_id = ?
              AND v.status = 'COMPLETED'
              AND v.scheduled_date >= ?
              AND v.scheduled_date < ?
            GROUP BY uf.root_id
        """.trimIndent()

        val result = mutableMapOf<UUID, Pair<Long, Long>>()
        jdbcTemplate.query(
            sql,
            { rs ->
                val id = rs.getObject("service_id", UUID::class.java)
                val orderCount = rs.getLong("order_count")
                val revenue = rs.getLong("total_revenue_gross")
                result[id] = orderCount to revenue
            },
            studioId,
            studioId,
            studioId,
            studioId,
            studioId,
            Timestamp.from(startDate),
            Timestamp.from(endDate)
        )
        return result
    }
}
