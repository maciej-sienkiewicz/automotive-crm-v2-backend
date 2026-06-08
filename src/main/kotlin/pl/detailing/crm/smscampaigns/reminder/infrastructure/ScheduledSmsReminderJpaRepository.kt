package pl.detailing.crm.smscampaigns.reminder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import java.time.Instant
import java.util.UUID

@Repository
interface ScheduledSmsReminderJpaRepository : JpaRepository<ScheduledSmsReminderEntity, UUID> {

    @Query("""
        SELECT r FROM ScheduledSmsReminderEntity r
        WHERE r.visitId = :visitId AND r.studioId = :studioId AND r.status = 'PENDING'
    """)
    fun findPendingByVisitId(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): ScheduledSmsReminderEntity?

    @Query("""
        SELECT r FROM ScheduledSmsReminderEntity r
        WHERE r.visitId = :visitId AND r.studioId = :studioId
        ORDER BY r.createdAt DESC
    """)
    fun findAllByVisitId(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): List<ScheduledSmsReminderEntity>

    @Query("""
        SELECT r FROM ScheduledSmsReminderEntity r
        WHERE r.status = 'PENDING' AND r.scheduledFor <= :now
    """)
    fun findDueForDispatch(@Param("now") now: Instant): List<ScheduledSmsReminderEntity>

    fun findAllByAppointmentId(appointmentId: UUID): List<ScheduledSmsReminderEntity>
}
