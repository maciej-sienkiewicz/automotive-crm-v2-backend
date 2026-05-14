package pl.detailing.crm.subscription.management

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface PendingPlanChangeRepository : JpaRepository<PendingPlanChangeEntity, UUID> {

    fun findByStudioIdAndStatus(studioId: UUID, status: PendingPlanChangeStatus): PendingPlanChangeEntity?

    /**
     * All PENDING rows whose effective date has arrived — processed by [PlanDowngradeScheduler].
     * Locks the rows for update to prevent double-processing in a multi-instance deployment.
     */
    @Query("""
        SELECT p FROM PendingPlanChangeEntity p
        WHERE p.status = 'PENDING'
          AND p.effectiveAt <= :now
        ORDER BY p.effectiveAt ASC
    """)
    fun findDueChanges(now: Instant): List<PendingPlanChangeEntity>

    @Modifying
    @Query("""
        UPDATE PendingPlanChangeEntity p
        SET p.status = 'CANCELLED'
        WHERE p.studioId = :studioId AND p.status = 'PENDING'
    """)
    fun cancelPendingForStudio(studioId: UUID): Int
}
