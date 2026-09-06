package pl.detailing.crm.smscampaigns.thankyou.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ScheduledThankYouSmsJpaRepository : JpaRepository<ScheduledThankYouSmsEntity, UUID> {

    fun existsByAppointmentId(appointmentId: UUID): Boolean

    @Query("""
        SELECT t FROM ScheduledThankYouSmsEntity t
        WHERE t.visitId = :visitId AND t.status = 'PENDING'
    """)
    fun findPendingByVisitId(@Param("visitId") visitId: UUID): ScheduledThankYouSmsEntity?

    @Query("""
        SELECT t FROM ScheduledThankYouSmsEntity t
        WHERE t.status = 'PENDING' AND t.scheduledFor <= :now
    """)
    fun findDueForDispatch(@Param("now") now: Instant): List<ScheduledThankYouSmsEntity>

    fun findAllByAppointmentId(appointmentId: UUID): List<ScheduledThankYouSmsEntity>
}
