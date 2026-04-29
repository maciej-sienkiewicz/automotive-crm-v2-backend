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

    @Query("""
        SELECT e FROM SmsAutomationConfigEntity e
        WHERE e.preVisitEnabled = true
           OR e.postVisitEnabled = true
           OR e.delayedReminderEnabled = true
           OR e.bookingConfirmationEnabled = true
           OR e.rescheduleConfirmationEnabled = true
    """)
    fun findAllWithAnyRuleEnabled(): List<SmsAutomationConfigEntity>
}
