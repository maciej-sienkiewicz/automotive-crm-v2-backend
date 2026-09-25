package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Repository
interface BatchOrderCloseHistoryRepository : JpaRepository<BatchOrderCloseHistoryEntity, UUID> {

    @Query("""
        SELECT h FROM BatchOrderCloseHistoryEntity h
        WHERE h.contractorId = :contractorId AND h.studioId = :studioId
        ORDER BY h.closedAt DESC
    """)
    fun findByContractorIdAndStudioId(contractorId: UUID, studioId: UUID): List<BatchOrderCloseHistoryEntity>

    /**
     * Okres i data każdego rozliczenia pracowni, bez reszty rekordu. Listy i przegląd
     * pytają tylko „kiedy ostatnio rozliczono" — ładowanie całych encji ciągnęłoby
     * snapshot_json każdego rozliczenia od początku działalności.
     */
    @Query("""
        SELECT new pl.detailing.crm.batchorder.infrastructure.SettlementStamp(h.contractorId, h.fromDate, h.toDate, h.closedAt)
        FROM BatchOrderCloseHistoryEntity h
        WHERE h.studioId = :studioId
        ORDER BY h.closedAt DESC
    """)
    fun findByStudioId(studioId: UUID): List<SettlementStamp>

    /** Jak [findByStudioId], dla jednego kontrahenta. */
    @Query("""
        SELECT new pl.detailing.crm.batchorder.infrastructure.SettlementStamp(h.contractorId, h.fromDate, h.toDate, h.closedAt)
        FROM BatchOrderCloseHistoryEntity h
        WHERE h.contractorId = :contractorId AND h.studioId = :studioId
        ORDER BY h.closedAt DESC
    """)
    fun findStampsByContractorIdAndStudioId(contractorId: UUID, studioId: UUID): List<SettlementStamp>

    @Query("SELECT h FROM BatchOrderCloseHistoryEntity h WHERE h.id = :id AND h.studioId = :studioId")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchOrderCloseHistoryEntity?
}

/** Rozliczenie widziane z listy: czyje, za jaki okres i kiedy. */
data class SettlementStamp(
    val contractorId: UUID,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val closedAt: Instant
)
