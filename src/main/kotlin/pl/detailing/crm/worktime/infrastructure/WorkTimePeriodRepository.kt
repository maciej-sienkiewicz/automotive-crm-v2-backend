package pl.detailing.crm.worktime.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
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
}
