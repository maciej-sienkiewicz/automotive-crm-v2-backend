package pl.detailing.crm.smscampaigns.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SmsAutomationConfigJpaRepository : JpaRepository<SmsAutomationConfigEntity, UUID> {

    @Query("SELECT e FROM SmsAutomationConfigEntity e WHERE e.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): SmsAutomationConfigEntity?

    /**
     * Only the three time-driven rules matter here — the scheduler polls for them.
     * Event-driven rules fire from their own handlers and never need this sweep.
     */
    @Query("""
        SELECT e FROM SmsAutomationConfigEntity e
        WHERE e.preVisitEnabled = true
           OR e.postVisitEnabled = true
           OR e.delayedReminderEnabled = true
    """)
    fun findAllWithAnyRuleEnabled(): List<SmsAutomationConfigEntity>
}
