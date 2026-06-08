package pl.detailing.crm.smscampaigns.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import java.util.UUID

@Repository
interface SmsLogJpaRepository : JpaRepository<SmsLogEntity, UUID> {

    @Query("""
        SELECT COUNT(l) > 0 FROM SmsLogEntity l
        WHERE l.appointmentId = :appointmentId
        AND l.triggerType = :triggerType
    """)
    fun existsByAppointmentIdAndTriggerType(
        @Param("appointmentId") appointmentId: UUID,
        @Param("triggerType") triggerType: SmsTriggerType
    ): Boolean

    fun findByAppointmentIdAndTriggerType(
        appointmentId: UUID,
        triggerType: SmsTriggerType
    ): SmsLogEntity?

    @Query("SELECT l FROM SmsLogEntity l WHERE l.appointmentId IN :appointmentIds")
    fun findAllByAppointmentIdIn(
        @Param("appointmentIds") appointmentIds: List<UUID>
    ): List<SmsLogEntity>

    fun findAllByAppointmentId(appointmentId: UUID): List<SmsLogEntity>
}
