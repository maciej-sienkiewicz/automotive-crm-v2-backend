package pl.detailing.crm.smscampaigns.infrastructure

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Read-only projection used by [pl.detailing.crm.smscampaigns.automation.SmsAutomationScheduler].
 * Contains only the fields required to render the SMS template and log the result.
 *
 * [customerId] is included so the scheduler can persist a [pl.detailing.crm.communication.infrastructure.CommunicationLogEntity]
 * linked to the customer even when no visit has been created yet (PRE_VISIT trigger).
 */
data class SmsAppointmentView(
    val appointmentId: UUID,
    val customerId: UUID,
    val appointmentStart: Instant,
    val appointmentEnd: Instant,
    val customerFirstName: String?,
    val customerLastName: String?,
    val customerPhone: String?,
    val studioName: String,
    val studioId: UUID
)

/**
 * Dedicated query service for the SMS automation module.
 *
 * Uses [EntityManager] directly so the [pl.detailing.crm.appointment.infrastructure.AppointmentRepository]
 * is not burdened with SMS-specific queries, keeping both modules independently evolvable.
 *
 * Only the PRE_VISIT rules read appointments. Everything that fires *after* a visit reads
 * visits instead (see [SmsVisitQueryService]) — an appointment ending is not evidence that
 * the customer ever showed up.
 */
@Service
class SmsAppointmentQueryService {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    companion object {
        /**
         * A reminder only makes sense for a booking that is still ahead of the customer.
         * CANCELLED / ABANDONED are dead bookings; CONVERTED means the car is already
         * with us, so "we are waiting for you" would be wrong.
         */
        private val EXCLUDED_STATUSES = listOf(
            AppointmentStatus.CANCELLED,
            AppointmentStatus.ABANDONED,
            AppointmentStatus.CONVERTED
        )
    }

    /** Appointments whose [startDateTime] falls inside [windowStart, windowEnd). */
    fun findByStudioIdAndStartTimeBetween(
        studioId: StudioId,
        windowStart: Instant,
        windowEnd: Instant
    ): List<SmsAppointmentView> = queryAppointments(studioId, windowStart, windowEnd)

    /**
     * Appointments across ALL studios where [sendReminderSms] was explicitly requested
     * and [startDateTime] falls inside the given window.
     *
     * Used by [pl.detailing.crm.smscampaigns.automation.SmsAutomationScheduler] to send
     * per-appointment reminders regardless of the studio-level PRE_VISIT automation config.
     */
    fun findWithSendReminderAndStartTimeBetween(
        windowStart: Instant,
        windowEnd: Instant
    ): List<SmsAppointmentView> {
        val excludedStatuses = EXCLUDED_STATUSES

        val jpql = """
            SELECT
                a.id,
                a.customerId,
                a.startDateTime,
                a.endDateTime,
                c.firstName,
                c.lastName,
                c.phone,
                s.name,
                a.studioId
            FROM AppointmentEntity a
            JOIN CustomerEntity   c ON c.id = a.customerId
            JOIN StudioEntity     s ON s.id = a.studioId
            WHERE a.sendReminderSms = true
            AND   a.deletedAt IS NULL
            AND   a.status NOT IN :excludedStatuses
            AND   a.startDateTime >= :windowStart
            AND   a.startDateTime <  :windowEnd
        """.trimIndent()

        val rows = entityManager.createQuery(jpql)
            .setParameter("excludedStatuses", excludedStatuses)
            .setParameter("windowStart", windowStart)
            .setParameter("windowEnd", windowEnd)
            .resultList

        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val cols = row as Array<Any?>
            SmsAppointmentView(
                appointmentId = cols[0] as UUID,
                customerId = cols[1] as UUID,
                appointmentStart = cols[2] as Instant,
                appointmentEnd = cols[3] as Instant,
                customerFirstName = cols[4] as String?,
                customerLastName = cols[5] as String?,
                customerPhone = cols[6] as String?,
                studioName = cols[7] as String,
                studioId = cols[8] as UUID
            )
        }
    }

    private fun queryAppointments(
        studioId: StudioId,
        windowStart: Instant,
        windowEnd: Instant
    ): List<SmsAppointmentView> {
        val excludedStatuses = EXCLUDED_STATUSES

        val jpql = """
            SELECT
                a.id,
                a.customerId,
                a.startDateTime,
                a.endDateTime,
                c.firstName,
                c.lastName,
                c.phone,
                s.name,
                a.studioId
            FROM AppointmentEntity a
            JOIN CustomerEntity   c ON c.id  = a.customerId
            JOIN StudioEntity     s ON s.id  = a.studioId
            WHERE a.studioId  = :studioId
            AND   a.deletedAt IS NULL
            AND   a.status NOT IN :excludedStatuses
            AND   a.startDateTime >= :windowStart
            AND   a.startDateTime <  :windowEnd
        """.trimIndent()

        val rows = entityManager.createQuery(jpql)
            .setParameter("studioId", studioId.value)
            .setParameter("excludedStatuses", excludedStatuses)
            .setParameter("windowStart", windowStart)
            .setParameter("windowEnd", windowEnd)
            .resultList

        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val cols = row as Array<Any?>
            SmsAppointmentView(
                appointmentId = cols[0] as UUID,
                customerId = cols[1] as UUID,
                appointmentStart = cols[2] as Instant,
                appointmentEnd = cols[3] as Instant,
                customerFirstName = cols[4] as String?,
                customerLastName = cols[5] as String?,
                customerPhone = cols[6] as String?,
                studioName = cols[7] as String,
                studioId = cols[8] as UUID
            )
        }
    }
}
