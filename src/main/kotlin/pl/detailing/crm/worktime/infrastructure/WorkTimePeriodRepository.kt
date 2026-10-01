package pl.detailing.crm.worktime.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface WorkTimePeriodRepository : JpaRepository<WorkTimePeriodEntity, UUID> {

    fun findByUserIdAndPeriod(userId: UUID, period: String): WorkTimePeriodEntity?

    /**
     * Tenant-scoped lookup for manager operations. A period reached through
     * `/worktime/team/{userId}/...` MUST be resolved with the caller's studio —
     * `userId` alone would let a manager approve or return another studio's card.
     */
    fun findByUserIdAndStudioIdAndPeriod(userId: UUID, studioId: UUID, period: String): WorkTimePeriodEntity?

    @Query("SELECT p FROM WorkTimePeriodEntity p WHERE p.userId = :userId ORDER BY p.period DESC")
    fun findByUserIdOrderByPeriodDesc(@Param("userId") userId: UUID): List<WorkTimePeriodEntity>

    @Query("SELECT p FROM WorkTimePeriodEntity p WHERE p.studioId = :studioId ORDER BY p.userId, p.period DESC")
    fun findByStudioIdOrderByUserIdAndPeriodDesc(@Param("studioId") studioId: UUID): List<WorkTimePeriodEntity>

    @Query("SELECT p FROM WorkTimePeriodEntity p WHERE p.userId = :userId AND p.studioId = :studioId ORDER BY p.period DESC")
    fun findByUserIdAndStudioIdOrderByPeriodDesc(
        @Param("userId") userId: UUID,
        @Param("studioId") studioId: UUID
    ): List<WorkTimePeriodEntity>

    /** Karty wszystkich osób za jeden miesiąc — przegląd miesiąca dla menedżera. */
    @Query("SELECT p FROM WorkTimePeriodEntity p WHERE p.studioId = :studioId AND p.period = :period")
    fun findByStudioIdAndPeriod(@Param("studioId") studioId: UUID, @Param("period") period: String): List<WorkTimePeriodEntity>

    /** Karty studia w jednym stanie — np. wszystkie złożone, czekające na decyzję. */
    @Query("SELECT p FROM WorkTimePeriodEntity p WHERE p.studioId = :studioId AND p.status = :status ORDER BY p.period")
    fun findByStudioIdAndStatus(
        @Param("studioId") studioId: UUID,
        @Param("status") status: PeriodStatus
    ): List<WorkTimePeriodEntity>

    /**
     * Zapisuje przypomnienie tylko wtedy, gdy poprzednie jest starsze niż [threshold] —
     * jednym zapytaniem, więc dwóch menedżerów klikających naraz nie wyśle dwóch push-y:
     * drugi dostaje 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE WorkTimePeriodEntity p SET p.remindedAt = :at
        WHERE p.id = :id AND (p.remindedAt IS NULL OR p.remindedAt <= :threshold)
        """
    )
    fun markReminded(@Param("id") id: UUID, @Param("at") at: Instant, @Param("threshold") threshold: Instant): Int
}
