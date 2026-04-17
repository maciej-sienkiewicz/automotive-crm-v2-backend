package pl.detailing.crm.smscampaigns.infrastructure

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Read-only projection used by [pl.detailing.crm.smscampaigns.automation.SmsAutomationScheduler]
 * for the DELAYED_REMINDER trigger.
 *
 * Contains only the fields required to render the SMS template and log the result.
 * The [appointmentId] is used for deduplication via [SmsLogJpaRepository] — the same
 * unique-constraint strategy as PRE_VISIT / POST_VISIT automations.
 */
data class SmsVisitView(
    val visitId: UUID,
    val appointmentId: UUID,
    val customerId: UUID,
    val pickupDate: Instant,
    val customerFirstName: String?,
    val customerPhone: String?,
    val studioName: String
)

/**
 * Dedicated query service for delayed-reminder SMS automation.
 *
 * Finds COMPLETED visits whose [pickupDate] falls within a given time window,
 * where the per-visit SMS reminder has not been suppressed.
 * Uses [EntityManager] directly to keep the [VisitRepository] free of
 * SMS-specific concerns.
 */
@Service
class SmsVisitQueryService {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    /**
     * Visits whose [pickupDate] falls inside [[windowStart], [windowEnd])
     * and for which [smsReminderSuppressed] is false.
     */
    fun findByStudioIdAndPickupDateBetween(
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
                c.firstName,
                c.phone,
                s.name
            FROM VisitEntity v
            JOIN CustomerEntity c ON c.id = v.customerId
            JOIN StudioEntity   s ON s.id = v.studioId
            WHERE v.studioId             = :studioId
            AND   v.smsReminderSuppressed = false
            AND   v.pickupDate           >= :windowStart
            AND   v.pickupDate           <  :windowEnd
        """.trimIndent()

        val rows = entityManager.createQuery(jpql)
            .setParameter("studioId", studioId.value)
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
                customerFirstName = cols[4] as String?,
                customerPhone = cols[5] as String?,
                studioName = cols[6] as String
            )
        }
    }
}
