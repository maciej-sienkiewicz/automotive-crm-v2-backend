package pl.detailing.crm.ksef.sync

import jakarta.persistence.*
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Per-studio sync state for the KSeF expense invoice synchronization.
 *
 * lastExpenseSync – timestamp of the last successful SUBJECT2 (expense) fetch;
 *                   used as the start point for delta sync (with 1-hour overlap).
 * syncStatus      – IDLE | RUNNING | ERROR
 * lastError       – last sync error message (max 2000 chars)
 *
 * When lastExpenseSync is null (first run), sync starts from credentials.createdAt
 * to prevent pulling historical data from before integration was activated.
 *
 * Stale-RUNNING detection: if status stays RUNNING for >30 min (e.g. after server restart),
 * the scheduler resets it to IDLE and re-runs the sync.
 */
@Entity
@Table(name = "ksef_sync_cursor")
class KsefSyncCursorEntity(

    @Id
    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Column(name = "last_expense_sync")
    val lastExpenseSync: OffsetDateTime? = null,

    @Column(name = "sync_status", nullable = false, length = 20)
    val syncStatus: String = "IDLE",

    @Column(name = "last_error", length = 2000)
    val lastError: String? = null,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    fun toRunning() = copy(syncStatus = "RUNNING", updatedAt = OffsetDateTime.now())

    fun toSuccess(syncedAt: OffsetDateTime) = copy(
        lastExpenseSync = syncedAt,
        syncStatus = "IDLE",
        lastError = null,
        updatedAt = OffsetDateTime.now()
    )

    fun toError(error: String) = copy(
        syncStatus = "ERROR",
        lastError = error.take(2000),
        updatedAt = OffsetDateTime.now()
    )

    fun toIdle() = copy(
        syncStatus = "IDLE",
        lastError = null,
        updatedAt = OffsetDateTime.now()
    )

    fun isStale(threshold: Duration): Boolean =
        syncStatus == "RUNNING" && updatedAt.isBefore(OffsetDateTime.now().minus(threshold))

    private fun copy(
        lastExpenseSync: OffsetDateTime? = this.lastExpenseSync,
        syncStatus: String = this.syncStatus,
        lastError: String? = this.lastError,
        updatedAt: OffsetDateTime = this.updatedAt
    ) = KsefSyncCursorEntity(studioId, lastExpenseSync, syncStatus, lastError, updatedAt)
}
