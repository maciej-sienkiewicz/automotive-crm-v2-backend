package pl.detailing.crm.inbound.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.CallLogStatus
import java.time.Instant
import java.util.*

@Repository
interface CallLogRepository : JpaRepository<CallLogEntity, UUID> {

    @Query("SELECT c FROM CallLogEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): CallLogEntity?

    @Query("""
        SELECT c FROM CallLogEntity c
        WHERE c.studioId = :studioId AND c.status = :status
        ORDER BY c.receivedAt DESC
    """)
    fun findByStudioIdAndStatus(
        @Param("studioId") studioId: UUID,
        @Param("status") status: CallLogStatus
    ): List<CallLogEntity>

    @Query("""
        SELECT c FROM CallLogEntity c
        WHERE c.studioId = :studioId AND c.status = 'PENDING'
        ORDER BY c.receivedAt DESC
    """)
    fun findPendingByStudioId(
        @Param("studioId") studioId: UUID
    ): List<CallLogEntity>

    @Query("""
        SELECT COUNT(c) FROM CallLogEntity c
        WHERE c.studioId = :studioId
        AND c.receivedAt >= :startDate
        AND c.receivedAt < :endDate
    """)
    fun countByStudioIdAndDateRange(
        @Param("studioId") studioId: UUID,
        @Param("startDate") startDate: Instant,
        @Param("endDate") endDate: Instant
    ): Long
}
