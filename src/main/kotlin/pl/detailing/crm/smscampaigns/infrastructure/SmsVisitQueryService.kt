package pl.detailing.crm.smscampaigns.infrastructure

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitStatus
import java.time.Instant
import java.util.UUID

/**
 * Read-only projection used by [pl.detailing.crm.smscampaigns.automation.SmsAutomationScheduler]
 * for the rules that fire *after the customer has collected their car* — POST_VISIT and
 * DELAYED_REMINDER.
 *
 * The [appointmentId] is used for deduplication via [SmsLogJpaRepository] — the same
 * unique-constraint strategy as the PRE_VISIT automation.
 */
data class SmsVisitView(
    val visitId: UUID,
    val appointmentId: UUID,
    val customerId: UUID,
    /** When the vehicle was handed back — the anchor both rules count from. */
    val pickupDate: Instant,
    /** When the visit itself took place — what {{data}} / {{godzina}} mean to a customer. */
    val scheduledDate: Instant,
    val customerFirstName: String?,
    val customerLastName: String?,
    val customerPhone: String?,
    val studioName: String
)

/**
 * Dedicated query service for the completion-driven SMS automations.
 *
 * Uses [EntityManager] directly to keep the VisitRepository free of SMS-specific concerns.
 */
@Service
class SmsVisitQueryService {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * Visits the customer has actually collected, whose [pickupDate] falls inside
     * [[windowStart], [windowEnd]).
     *
     * The status filter is what keeps "thank you for your visit" away from a booking the
     * customer never turned up for: a reservation that was never checked in has no visit
     * at all, and a visit that never reached COMPLETED has no pickupDate. Visits the studio
     * muted individually (smsReminderSuppressed) are excluded, as are deleted ones.
     */
    fun findCompletedByStudioIdAndPickupDateBetween(
        studioId: StudioId,
        windowStart: Instant,
        windowEnd: Instant
    ): List<SmsVisitView> {
        val jpql = """
            SELECT
                v.id,
                v.appointmentId,
                v.customerId,
                v.pickupDate,
                v.scheduledDate,
                c.firstName,
                c.lastName,
                c.phone,
                s.name
            FROM VisitEntity v
            JOIN CustomerEntity c ON c.id = v.customerId
            JOIN StudioEntity   s ON s.id = v.studioId
            WHERE v.studioId              = :studioId
            AND   v.status                = :completed
            AND   v.deletedAt            IS NULL
            AND   v.smsReminderSuppressed = false
            AND   v.pickupDate           >= :windowStart
            AND   v.pickupDate           <  :windowEnd
        """.trimIndent()

        val rows = entityManager.createQuery(jpql)
            .setParameter("studioId", studioId.value)
            .setParameter("completed", VisitStatus.COMPLETED)
            .setParameter("windowStart", windowStart)
            .setParameter("windowEnd", windowEnd)
            .resultList

        return rows.map { row ->
            @Suppress("UNCHECKED_CAST")
            val cols = row as Array<Any?>
            SmsVisitView(
                visitId = cols[0] as UUID,
                appointmentId = cols[1] as UUID,
                customerId = cols[2] as UUID,
                pickupDate = cols[3] as Instant,
                scheduledDate = cols[4] as Instant,
                customerFirstName = cols[5] as String?,
                customerLastName = cols[6] as String?,
                customerPhone = cols[7] as String?,
                studioName = cols[8] as String
            )
        }
    }
}
