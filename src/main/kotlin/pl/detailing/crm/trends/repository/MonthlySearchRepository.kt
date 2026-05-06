package pl.detailing.crm.trends.repository

import pl.detailing.crm.trends.domain.MonthlySearch
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class MonthlySearchRepository(private val jdbc: JdbcTemplate) {

    fun upsert(keywordId: Long, locationCode: Int, year: Int, month: Int, volume: Int?) {
        jdbc.update(
            """INSERT INTO monthly_searches (keyword_id, location_code, year, month, search_volume)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT (keyword_id, location_code, year, month)
               DO UPDATE SET search_volume = EXCLUDED.search_volume""",
            keywordId, locationCode, year, month, volume
        )
    }

    fun findByKeywordAndLocation(keywordId: Long, locationCode: Int): List<MonthlySearch> =
        jdbc.query(
            "SELECT * FROM monthly_searches WHERE keyword_id = ? AND location_code = ? ORDER BY year, month",
            ROW_MAPPER, keywordId, locationCode
        )

    /** Returns (year, month) of the latest known monthly entry for a keyword, or null if none. */
    fun findLatestMonth(keywordId: Long): Pair<Int, Int>? =
        jdbc.query(
            "SELECT year, month FROM monthly_searches WHERE keyword_id = ? ORDER BY year DESC, month DESC LIMIT 1",
            { rs, _ -> rs.getInt("year") to rs.getInt("month") },
            keywordId
        ).firstOrNull()

    companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _ ->
            MonthlySearch(
                id = rs.getLong("id"),
                keywordId = rs.getLong("keyword_id"),
                locationCode = rs.getInt("location_code"),
                year = rs.getInt("year"),
                month = rs.getInt("month"),
                searchVolume = rs.getObject("search_volume") as? Int
            )
        }
    }
}
