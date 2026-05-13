package pl.detailing.crm.ksef.sync

import jakarta.persistence.*
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Przechowuje stan synchronizacji KSeF per studio (multi-tenant cursor).
 *
 * lastExpenseSync – data ostatniego udanego fetchu SUBJECT2 (koszty); punkt startowy delta-sync
 * lastIncomeSync  – zachowane dla zgodności wstecznej, nie jest aktualizowane przez scheduler
 * syncStatus      – IDLE | RUNNING | ERROR
 * lastError       – ostatni błąd synchronizacji (max 2000 znaków)
 *
 * Gdy lastExpenseSync == null (pierwszy sync), punkt zero wyznacza credentials.createdAt,
 * co gwarantuje brak pobierania danych historycznych sprzed aktywacji integracji.
 *
 * Scheduler używa tego rekordu do delta sync – pobiera faktury od (lastExpenseSync - 1h) do teraz,
 * co zabezpiecza przed przegapieniem faktur z opóźnieniami po stronie KSeF.
 *
 * Mechanizm stale-RUNNING: jeśli status = RUNNING przez dłużej niż [isStale] próg
 * (np. po restarcie serwera w trakcie synca), scheduler może zresetować go do IDLE
 * i zainicjować nowy sync bez cofania się do danych historycznych.
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

    /** Aktualizuje tylko lastExpenseSync – INCOME nie jest już synchronizowane przez scheduler. */
    fun toSuccess(syncedAt: OffsetDateTime): KsefSyncCursorEntity = copy(
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

    /** Resetuje "zawieszony" stan RUNNING do IDLE (np. po restarcie serwera). */
    fun toIdle(): KsefSyncCursorEntity = copy(
        syncStatus = "IDLE",
        lastError = null,
        updatedAt = OffsetDateTime.now()
    )

    /**
     * Zwraca true jeśli status = RUNNING i rekord nie był aktualizowany przez dłużej niż [threshold].
     * Służy do wykrywania "zamarłych" synców po restarcie serwera.
     */
    fun isStale(threshold: Duration): Boolean =
        syncStatus == "RUNNING" && updatedAt.isBefore(OffsetDateTime.now().minus(threshold))

    private fun copy(
        lastIncomeSync: OffsetDateTime? = this.lastIncomeSync,
        lastExpenseSync: OffsetDateTime? = this.lastExpenseSync,
        syncStatus: String = this.syncStatus,
        lastError: String? = this.lastError,
        updatedAt: OffsetDateTime = this.updatedAt
    ) = KsefSyncCursorEntity(studioId, lastIncomeSync, lastExpenseSync, syncStatus, lastError, updatedAt)
}
