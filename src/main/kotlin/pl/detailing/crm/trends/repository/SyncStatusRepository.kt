package pl.detailing.crm.trends.repository

import pl.detailing.crm.trends.domain.SyncStatus
import pl.detailing.crm.trends.domain.SyncTaskStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class SyncStatusRepository(private val jdbc: JdbcTemplate) {

    fun find(taskName: String): SyncStatus? =
        jdbc.query(
            "SELECT * FROM sync_status WHERE task_name = ?",
            ROW_MAPPER, taskName
        ).firstOrNull()

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
        val ROW_MAPPER = RowMapper { rs: ResultSet, _ ->
            SyncStatus(
                taskName = rs.getString("task_name"),
                lastRunAt = rs.getTimestamp("last_run_at")?.toInstant(),
                lastSuccessAt = rs.getTimestamp("last_success_at")?.toInstant(),
                status = SyncTaskStatus.valueOf(rs.getString("status")),
                details = rs.getString("details")
            )
        }
    }
}
