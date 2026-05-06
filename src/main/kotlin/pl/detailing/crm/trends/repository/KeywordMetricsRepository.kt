package pl.detailing.crm.trends.repository

import pl.detailing.crm.trends.domain.KeywordMetric
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class KeywordMetricsRepository(private val jdbc: JdbcTemplate) {

    fun upsert(metric: KeywordMetric) {
        jdbc.update(
            """INSERT INTO keyword_metrics
               (keyword_id, location_code, search_volume, cpc, competition, competition_index,
                low_top_of_page_bid, high_top_of_page_bid, fetched_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
               ON CONFLICT (keyword_id, location_code) DO UPDATE SET
                 search_volume        = EXCLUDED.search_volume,
                 cpc                  = EXCLUDED.cpc,
                 competition          = EXCLUDED.competition,
                 competition_index    = EXCLUDED.competition_index,
                 low_top_of_page_bid  = EXCLUDED.low_top_of_page_bid,
                 high_top_of_page_bid = EXCLUDED.high_top_of_page_bid,
                 fetched_at           = NOW()""",
            metric.keywordId, metric.locationCode, metric.searchVolume, metric.cpc,
            metric.competition, metric.competitionIndex,
            metric.lowTopOfPageBid, metric.highTopOfPageBid
        )
    }

    fun findByKeywordId(keywordId: Long): List<KeywordMetric> =
        jdbc.query(
            "SELECT * FROM keyword_metrics WHERE keyword_id = ?",
            ROW_MAPPER, keywordId
        )

    fun findByLocationCode(locationCode: Int): List<KeywordMetric> =
        jdbc.query(
            "SELECT * FROM keyword_metrics WHERE location_code = ? ORDER BY search_volume DESC NULLS LAST",
            ROW_MAPPER, locationCode
        )

    companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _ ->
            KeywordMetric(
                id = rs.getLong("id"),
                keywordId = rs.getLong("keyword_id"),
                locationCode = rs.getInt("location_code"),
                searchVolume = rs.getObject("search_volume") as? Int,
                cpc = rs.getObject("cpc") as? Double,
                competition = rs.getString("competition"),
                competitionIndex = rs.getObject("competition_index") as? Int,
                lowTopOfPageBid = rs.getObject("low_top_of_page_bid") as? Double,
                highTopOfPageBid = rs.getObject("high_top_of_page_bid") as? Double,
                fetchedAt = rs.getTimestamp("fetched_at").toInstant()
            )
        }
    }
}
