package com.example.demo.trends.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

// ═══════════════════════════════════════════════════════════════════════
// DOMAIN ENTITIES
// ═══════════════════════════════════════════════════════════════════════

data class TrackedKeywordEntity(
    val id: Long,
    val keyword: String,
    val status: String,
    val source: String,
    val addedAt: Instant,
    val lastFetchedAt: Instant?
)

data class KeywordMetricEntity(
    val id: Long,
    val keywordId: Long,
    val locationCode: Int,
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?,
    val competitionIndex: Int?,
    val lowTopOfPageBid: Double?,
    val highTopOfPageBid: Double?,
    val fetchedAt: Instant
)

data class MonthlySearchEntity(
    val id: Long,
    val keywordId: Long,
    val locationCode: Int,
    val year: Int,
    val month: Int,
    val searchVolume: Int?
)

data class TrendDataEntity(
    val id: Long,
    val keywordId: Long,
    val date: LocalDate,
    val trendIndex: Int?,
    val locationCode: Int
)

data class SyncStatusEntity(
    val taskName: String,
    val lastRunAt: Instant?,
    val lastSuccessAt: Instant?,
    val status: String,
    val details: String?
)

// ═══════════════════════════════════════════════════════════════════════
// REPOSITORIES
// ═══════════════════════════════════════════════════════════════════════

@Repository
class TrackedKeywordRepository(private val jdbc: JdbcTemplate) {

    fun findAll(): List<TrackedKeywordEntity> =
        jdbc.query("SELECT * FROM tracked_keywords ORDER BY keyword", KEYWORD_MAPPER)

    fun findByStatus(status: String): List<TrackedKeywordEntity> =
        jdbc.query("SELECT * FROM tracked_keywords WHERE status = ? ORDER BY keyword", KEYWORD_MAPPER, status)

    fun findByKeyword(keyword: String): TrackedKeywordEntity? =
        jdbc.query("SELECT * FROM tracked_keywords WHERE keyword = ?", KEYWORD_MAPPER, keyword).firstOrNull()

    fun insertIfNotExists(keyword: String, source: String = "SEED"): Long {
        jdbc.update(
            "INSERT INTO tracked_keywords (keyword, source) VALUES (?, ?) ON CONFLICT (keyword) DO NOTHING",
            keyword, source
        )
        return jdbc.queryForObject(
            "SELECT id FROM tracked_keywords WHERE keyword = ?", Long::class.java, keyword
        )!!
    }

    fun updateStatus(id: Long, status: String) {
        jdbc.update("UPDATE tracked_keywords SET status = ? WHERE id = ?", status, id)
    }

    fun updateLastFetched(id: Long) {
        jdbc.update("UPDATE tracked_keywords SET last_fetched_at = NOW() WHERE id = ?", id)
    }

    fun countByStatus(status: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM tracked_keywords WHERE status = ?", Int::class.java, status) ?: 0

    companion object {
        val KEYWORD_MAPPER = RowMapper { rs: ResultSet, _ ->
            TrackedKeywordEntity(
                id = rs.getLong("id"),
                keyword = rs.getString("keyword"),
                status = rs.getString("status"),
                source = rs.getString("source"),
                addedAt = rs.getTimestamp("added_at").toInstant(),
                lastFetchedAt = rs.getTimestamp("last_fetched_at")?.toInstant()
            )
        }
    }
}

@Repository
class KeywordMetricsRepository(private val jdbc: JdbcTemplate) {

    fun upsert(entity: KeywordMetricEntity) {
        jdbc.update(
            """INSERT INTO keyword_metrics 
               (keyword_id, location_code, search_volume, cpc, competition, competition_index, 
                low_top_of_page_bid, high_top_of_page_bid, fetched_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
               ON CONFLICT (keyword_id, location_code) 
               DO UPDATE SET search_volume = EXCLUDED.search_volume, cpc = EXCLUDED.cpc,
                  competition = EXCLUDED.competition, competition_index = EXCLUDED.competition_index,
                  low_top_of_page_bid = EXCLUDED.low_top_of_page_bid,
                  high_top_of_page_bid = EXCLUDED.high_top_of_page_bid,
                  fetched_at = NOW()""",
            entity.keywordId, entity.locationCode, entity.searchVolume, entity.cpc,
            entity.competition, entity.competitionIndex,
            entity.lowTopOfPageBid, entity.highTopOfPageBid
        )
    }

    fun findByKeywordId(keywordId: Long): List<KeywordMetricEntity> =
        jdbc.query(
            "SELECT * FROM keyword_metrics WHERE keyword_id = ?",
            METRIC_MAPPER, keywordId
        )

    fun findByLocationCode(locationCode: Int): List<KeywordMetricEntity> =
        jdbc.query(
            "SELECT * FROM keyword_metrics WHERE location_code = ? ORDER BY search_volume DESC NULLS LAST",
            METRIC_MAPPER, locationCode
        )

    companion object {
        val METRIC_MAPPER = RowMapper { rs: ResultSet, _ ->
            KeywordMetricEntity(
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

    fun findByKeywordAndLocation(keywordId: Long, locationCode: Int): List<MonthlySearchEntity> =
        jdbc.query(
            "SELECT * FROM monthly_searches WHERE keyword_id = ? AND location_code = ? ORDER BY year, month",
            MONTHLY_MAPPER, keywordId, locationCode
        )

    fun findLatestMonth(keywordId: Long): Pair<Int, Int>? {
        val result = jdbc.query(
            "SELECT year, month FROM monthly_searches WHERE keyword_id = ? ORDER BY year DESC, month DESC LIMIT 1",
            { rs, _ -> rs.getInt("year") to rs.getInt("month") }, keywordId
        )
        return result.firstOrNull()
    }

    companion object {
        val MONTHLY_MAPPER = RowMapper { rs: ResultSet, _ ->
            MonthlySearchEntity(
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

@Repository
class TrendDataRepository(private val jdbc: JdbcTemplate) {

    fun upsert(keywordId: Long, date: LocalDate, trendIndex: Int?, locationCode: Int = 2616) {
        jdbc.update(
            """INSERT INTO trend_data (keyword_id, date, trend_index, location_code)
               VALUES (?, ?, ?, ?)
               ON CONFLICT (keyword_id, date, location_code) DO UPDATE SET trend_index = EXCLUDED.trend_index""",
            keywordId, java.sql.Date.valueOf(date), trendIndex, locationCode
        )
    }

    fun findByKeyword(keywordId: Long, from: LocalDate? = null, to: LocalDate? = null): List<TrendDataEntity> {
        val sql = buildString {
            append("SELECT * FROM trend_data WHERE keyword_id = ?")
            if (from != null) append(" AND date >= ?")
            if (to != null) append(" AND date <= ?")
            append(" ORDER BY date")
        }
        val params = mutableListOf<Any>(keywordId)
        if (from != null) params.add(java.sql.Date.valueOf(from))
        if (to != null) params.add(java.sql.Date.valueOf(to))

        return jdbc.query(sql, TREND_MAPPER, *params.toTypedArray())
    }

    companion object {
        val TREND_MAPPER = RowMapper { rs: ResultSet, _ ->
            TrendDataEntity(
                id = rs.getLong("id"),
                keywordId = rs.getLong("keyword_id"),
                date = rs.getDate("date").toLocalDate(),
                trendIndex = rs.getObject("trend_index") as? Int,
                locationCode = rs.getInt("location_code")
            )
        }
    }
}

@Repository
class SyncStatusRepository(private val jdbc: JdbcTemplate) {

    fun get(taskName: String): SyncStatusEntity? =
        jdbc.query("SELECT * FROM sync_status WHERE task_name = ?", SYNC_MAPPER, taskName).firstOrNull()

    fun markRunning(taskName: String) {
        jdbc.update(
            """INSERT INTO sync_status (task_name, last_run_at, status)
               VALUES (?, NOW(), 'RUNNING')
               ON CONFLICT (task_name) DO UPDATE SET last_run_at = NOW(), status = 'RUNNING'""",
            taskName
        )
    }

    fun markSuccess(taskName: String, details: String? = null) {
        jdbc.update(
            "UPDATE sync_status SET status = 'IDLE', last_success_at = NOW(), details = ? WHERE task_name = ?",
            details, taskName
        )
    }

    fun markFailed(taskName: String, error: String?) {
        jdbc.update(
            "UPDATE sync_status SET status = 'FAILED', details = ? WHERE task_name = ?",
            error, taskName
        )
    }

    companion object {
        val SYNC_MAPPER = RowMapper { rs: ResultSet, _ ->
            SyncStatusEntity(
                taskName = rs.getString("task_name"),
                lastRunAt = rs.getTimestamp("last_run_at")?.toInstant(),
                lastSuccessAt = rs.getTimestamp("last_success_at")?.toInstant(),
                status = rs.getString("status"),
                details = rs.getString("details")
            )
        }
    }
}

