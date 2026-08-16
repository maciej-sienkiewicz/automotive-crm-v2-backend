package pl.detailing.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * One-shot, idempotent repair of the provisioning invariant:
 * "every studio has exactly one `studio_subscription_plans` row".
 *
 * Studios created before the invariant existed (signup used to leave the row
 * out entirely; trials never created one) are backfilled with the BASIC
 * feature-plan — exactly what the old silent fallback in EntitlementService
 * pretended they had, so no studio gains or loses any feature.
 *
 * Runs on every startup after [EntitlementDataSeeder] (the BASIC plan row must
 * exist). On a healthy database it selects zero rows and does nothing, so the
 * cost is one indexed anti-join query. It intentionally does NOT touch billing
 * status (`studios.subscription_status`) — that remains the billing lifecycle's
 * concern.
 */
@Component
@DependsOn("entitlementDataSeeder")
class StudioSubscriptionBackfill(
    private val jdbcTemplate: JdbcTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    @Transactional
    fun backfillMissingPlanRows() {
        val basicPlanId = jdbcTemplate.query(
            "SELECT id FROM subscription_plans WHERE plan_key = 'BASIC'"
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()

        if (basicPlanId == null) {
            logger.error("Backfill skipped: BASIC plan not found in subscription_plans — seeder failure?")
            return
        }

        val orphanedStudioIds = jdbcTemplate.query(
            """
            SELECT s.id FROM studios s
            WHERE NOT EXISTS (
                SELECT 1 FROM studio_subscription_plans sp WHERE sp.studio_id = s.id
            )
            """.trimIndent()
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }

        if (orphanedStudioIds.isEmpty()) {
            logger.debug("Provisioning invariant holds — no studios to backfill")
            return
        }

        val now = Timestamp.from(Instant.now())
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO studio_subscription_plans (id, studio_id, plan_id, activated_at, created_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (studio_id) DO NOTHING
            """.trimIndent(),
            orphanedStudioIds.map { studioId ->
                arrayOf<Any>(UUID.randomUUID(), studioId, basicPlanId, now, now)
            }
        )

        logger.warn(
            "Backfilled BASIC subscription-plan rows for {} studio(s) missing them: {}",
            orphanedStudioIds.size, orphanedStudioIds
        )
    }
}
