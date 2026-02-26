package pl.detailing.crm.ksef.sync

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Przechowuje stan synchronizacji KSeF per studio (multi-tenant cursor).
 *
 * lastIncomeSync  – data ostatniego udanego fetchu SUBJECT1 (przychody)
 * lastExpenseSync – data ostatniego udanego fetchu SUBJECT2 (koszty)
 * syncStatus      – IDLE | RUNNING | ERROR
 * lastError       – ostatni błąd synchronizacji (max 2000 znaków)
 *
 * Scheduler używa tego rekordu do delta sync – pobiera tylko faktury
 * od (lastSync - 1h) do teraz, żeby uniknąć duplikatów i przegapień.
 */
@Entity
@Table(name = "ksef_sync_cursor")
class KsefSyncCursorEntity(

    @Id
    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Column(name = "last_income_sync")
    val lastIncomeSync: OffsetDateTime? = null,

    @Column(name = "last_expense_sync")
    val lastExpenseSync: OffsetDateTime? = null,

    /** IDLE | RUNNING | ERROR */
    @Column(name = "sync_status", nullable = false, length = 20)
    val syncStatus: String = "IDLE",

    @Column(name = "last_error", length = 2000)
    val lastError: String? = null,

    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime = OffsetDateTime.now()
) {
    fun toRunning(): KsefSyncCursorEntity = copy(syncStatus = "RUNNING", updatedAt = OffsetDateTime.now())

    fun toSuccess(syncedAt: OffsetDateTime): KsefSyncCursorEntity = copy(
        lastIncomeSync = syncedAt,
        lastExpenseSync = syncedAt,
        syncStatus = "IDLE",
        lastError = null,
        updatedAt = OffsetDateTime.now()
    )

    fun toError(error: String): KsefSyncCursorEntity = copy(
        syncStatus = "ERROR",
        lastError = error.take(2000),
        updatedAt = OffsetDateTime.now()
    )

    private fun copy(
        lastIncomeSync: OffsetDateTime? = this.lastIncomeSync,
        lastExpenseSync: OffsetDateTime? = this.lastExpenseSync,
        syncStatus: String = this.syncStatus,
        lastError: String? = this.lastError,
        updatedAt: OffsetDateTime = this.updatedAt
    ) = KsefSyncCursorEntity(studioId, lastIncomeSync, lastExpenseSync, syncStatus, lastError, updatedAt)
}
