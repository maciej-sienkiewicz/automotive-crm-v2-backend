package pl.detailing.crm.trends.repository

import pl.detailing.crm.trends.domain.KeywordSource
import pl.detailing.crm.trends.domain.KeywordStatus
import pl.detailing.crm.trends.domain.TrackedKeyword
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class TrackedKeywordRepository(private val jdbc: JdbcTemplate) {

    fun findAll(): List<TrackedKeyword> =
        jdbc.query("SELECT * FROM tracked_keywords ORDER BY keyword", ROW_MAPPER)

    fun findByStatus(status: KeywordStatus): List<TrackedKeyword> =
        jdbc.query(
            "SELECT * FROM tracked_keywords WHERE status = ? ORDER BY keyword",
            ROW_MAPPER, status.name
        )

    fun findByKeyword(keyword: String): TrackedKeyword? =
        jdbc.query(
            "SELECT * FROM tracked_keywords WHERE keyword = ?",
            ROW_MAPPER, keyword
        ).firstOrNull()

    fun insertIfNotExists(keyword: String, source: KeywordSource = KeywordSource.SEED): Long {
        jdbc.update(
            "INSERT INTO tracked_keywords (keyword, source) VALUES (?, ?) ON CONFLICT (keyword) DO NOTHING",
            keyword, source.name
        )
        return jdbc.queryForObject(
            "SELECT id FROM tracked_keywords WHERE keyword = ?", Long::class.java, keyword
        )!!
    }

    fun updateStatus(id: Long, status: KeywordStatus) {
        jdbc.update("UPDATE tracked_keywords SET status = ? WHERE id = ?", status.name, id)
    }

    fun updateLastFetched(id: Long) {
        jdbc.update("UPDATE tracked_keywords SET last_fetched_at = NOW() WHERE id = ?", id)
    }

    fun countByStatus(status: KeywordStatus): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM tracked_keywords WHERE status = ?",
            Int::class.java, status.name
        ) ?: 0

    companion object {
        val ROW_MAPPER = RowMapper { rs: ResultSet, _ ->
            TrackedKeyword(
                id = rs.getLong("id"),
                keyword = rs.getString("keyword"),
                status = KeywordStatus.valueOf(rs.getString("status")),
                source = KeywordSource.valueOf(rs.getString("source")),
                addedAt = rs.getTimestamp("added_at").toInstant(),
                lastFetchedAt = rs.getTimestamp("last_fetched_at")?.toInstant()
            )
        }
    }
}
