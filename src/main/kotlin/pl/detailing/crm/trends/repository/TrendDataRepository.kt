package pl.detailing.crm.trends.repository

import pl.detailing.crm.trends.domain.TrendDataPoint
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDate

@Repository
class TrendDataRepository(private val jdbc: JdbcTemplate) {

    fun upsert(keywordId: Long, date: LocalDate, trendIndex: Int?, locationCode: Int = POLAND_LOCATION_CODE) {
        jdbc.update(
            """INSERT INTO trend_data (keyword_id, date, trend_index, location_code)
               VALUES (?, ?, ?, ?)
               ON CONFLICT (keyword_id, date, location_code) DO UPDATE SET trend_index = EXCLUDED.trend_index""",
            keywordId, java.sql.Date.valueOf(date), trendIndex, locationCode
        )
    }

    fun findByKeyword(keywordId: Long, from: LocalDate? = null, to: LocalDate? = null): List<TrendDataPoint> {
        val conditions = buildList {
            add("keyword_id = ?")
            if (from != null) add("date >= ?")
            if (to != null) add("date <= ?")
        }
        val sql = "SELECT * FROM trend_data WHERE ${conditions.joinToString(" AND ")} ORDER BY date"
        val params = buildList<Any> {
            add(keywordId)
            if (from != null) add(java.sql.Date.valueOf(from))
            if (to != null) add(java.sql.Date.valueOf(to))
        }
        return jdbc.query(sql, ROW_MAPPER, *params.toTypedArray())
    }

    /** Latest trend_index per keyword — one row per keyword_id (DISTINCT ON, PostgreSQL). */
    fun findLatestIndexByKeywords(keywordIds: List<Long>, locationCode: Int): Map<Long, Int?> {
        if (keywordIds.isEmpty()) return emptyMap()
        val inClause = keywordIds.joinToString(",") { "?" }
        val sql = """
            SELECT DISTINCT ON (keyword_id) keyword_id, trend_index
            FROM trend_data
            WHERE keyword_id IN ($inClause)
              AND location_code = ?
            ORDER BY keyword_id, date DESC
        """.trimIndent()
        val params = keywordIds.map { it as Any } + locationCode
        return jdbc.query(sql, { rs, _ ->
            rs.getLong("keyword_id") to (rs.getObject("trend_index") as? Int)
        }, *params.toTypedArray()).toMap()
    }

    /** Average trend_index per keyword over a date window. */
    fun findAverageIndexByKeywords(
        keywordIds: List<Long>,
        from: LocalDate,
        to: LocalDate,
        locationCode: Int
    ): Map<Long, Double?> {
        if (keywordIds.isEmpty()) return emptyMap()
        val inClause = keywordIds.joinToString(",") { "?" }
        val sql = """
            SELECT keyword_id, AVG(trend_index) AS avg_index
            FROM trend_data
            WHERE keyword_id IN ($inClause)
              AND location_code = ?
              AND date >= ?
              AND date <= ?
            GROUP BY keyword_id
        """.trimIndent()
        val params = keywordIds.map { it as Any } + locationCode +
                java.sql.Date.valueOf(from) + java.sql.Date.valueOf(to)
        return jdbc.query(sql, { rs, _ ->
            rs.getLong("keyword_id") to rs.getObject("avg_index") as? Double
        }, *params.toTypedArray()).toMap()
    }

    companion object {
        const val POLAND_LOCATION_CODE = 2616

        val ROW_MAPPER = RowMapper { rs: ResultSet, _ ->
            TrendDataPoint(
                id            = rs.getLong("id"),
                keywordId     = rs.getLong("keyword_id"),
                date          = rs.getDate("date").toLocalDate(),
                trendIndex    = rs.getObject("trend_index") as? Int,
                locationCode  = rs.getInt("location_code")
            )
        }
    }
}
