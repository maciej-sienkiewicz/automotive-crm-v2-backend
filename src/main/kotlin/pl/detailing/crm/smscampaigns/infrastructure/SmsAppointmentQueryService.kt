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
 */
data class SmsAppointmentView(
    val appointmentId: UUID,
    val appointmentStart: Instant,
    val appointmentEnd: Instant,
    val customerFirstName: String?,
    val customerPhone: String?,
    val studioName: String
)

/**
 * Dedicated query service for the SMS automation module.
 *
 * Uses [EntityManager] directly so the [pl.detailing.crm.appointment.infrastructure.AppointmentRepository]
 * is not burdened with SMS-specific queries, keeping both modules independently evolvable.
 *
 * Both queries join appointments → customers → studios in a single round-trip and exclude
 * CANCELLED / ABANDONED appointments to avoid sending SMS for dead appointments.
 */
@Service
class SmsAppointmentQueryService {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /** Appointments whose [startDateTime] falls inside [windowStart, windowEnd). */
    fun findByStudioIdAndStartTimeBetween(
        studioId: StudioId,
        windowStart: Instant,
        windowEnd: Instant
    ): List<SmsAppointmentView> = queryAppointments(
        studioId = studioId,
        windowStart = windowStart,
        windowEnd = windowEnd,
        useStartTime = true
    )

    /** Appointments whose [endDateTime] falls inside [windowStart, windowEnd). */
    fun findByStudioIdAndEndTimeBetween(
        studioId: StudioId,
        windowStart: Instant,
        windowEnd: Instant
    ): List<SmsAppointmentView> = queryAppointments(
        studioId = studioId,
        windowStart = windowStart,
        windowEnd = windowEnd,
        useStartTime = false
    )

    private fun queryAppointments(
        studioId: StudioId,
        windowStart: Instant,
        windowEnd: Instant,
        useStartTime: Boolean
    ): List<SmsAppointmentView> {
        val timeField = if (useStartTime) "a.startDateTime" else "a.endDateTime"

        val excludedStatuses = listOf(AppointmentStatus.CANCELLED, AppointmentStatus.ABANDONED)

        val jpql = """
            SELECT
                a.id,
                a.startDateTime,
                a.endDateTime,
                c.firstName,
                c.phone,
                s.name
            FROM AppointmentEntity a
            JOIN CustomerEntity   c ON c.id  = a.customerId
            JOIN StudioEntity     s ON s.id  = a.studioId
            WHERE a.studioId  = :studioId
            AND   a.deletedAt IS NULL
            AND   a.status NOT IN :excludedStatuses
            AND   $timeField >= :windowStart
            AND   $timeField <  :windowEnd
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
                appointmentStart = cols[1] as Instant,
                appointmentEnd = cols[2] as Instant,
                customerFirstName = cols[3] as String?,
                customerPhone = cols[4] as String?,
                studioName = cols[5] as String
            )
        }
    }
}
