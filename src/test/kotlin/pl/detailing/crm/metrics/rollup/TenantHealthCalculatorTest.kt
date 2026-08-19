package pl.detailing.crm.metrics.rollup

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.detailing.crm.metrics.domain.ChurnRisk
import java.util.UUID

/**
 * The health score is a product judgement expressed as code, so it is tested the way a
 * product rule should be: against the customer situations it exists to distinguish.
 */
class TenantHealthCalculatorTest {

    private val calculator = TenantHealthCalculator(mockk<NamedParameterJdbcTemplate>(relaxed = true))

    private fun inputs(
        daysSinceActivity: Int = 0,
        recentMinutes: Long = 1_000,
        priorMinutes: Long = 1_000,
        recentReservations: Long = 25,
        usersTotal: Int = 4,
        usersActive14d: Int = 4,
        errors14d: Long = 0,
        accountAgeDays: Int = 200
    ) = TenantHealthCalculator.HealthInputs(
        studioId = UUID.randomUUID(),
        daysSinceActivity = daysSinceActivity,
        recentMinutes = recentMinutes,
        priorMinutes = priorMinutes,
        recentReservations = recentReservations,
        usersTotal = usersTotal,
        usersActive14d = usersActive14d,
        errors14d = errors14d,
        accountAgeDays = accountAgeDays
    )

    @Test
    fun `a thriving studio scores near the top`() {
        val score = calculator.score(inputs())

        // 95, not 100: engagement flat against the previous fortnight scores the "steady"
        // band rather than the "growing" one. Deliberate — a studio holding level is
        // healthy, but it is not the same signal as one whose usage is climbing.
        assertEquals(95, score)
        assertEquals(ChurnRisk.HEALTHY, ChurnRisk.fromScore(score))
    }

    @Test
    fun `an abandoned account scores at the bottom`() {
        val score = calculator.score(
            inputs(
                daysSinceActivity = 60, recentMinutes = 0, priorMinutes = 800,
                recentReservations = 0, usersActive14d = 0, errors14d = 0
            )
        )

        assertEquals(ChurnRisk.CRITICAL, ChurnRisk.fromScore(score))
    }

    @Test
    fun `a studio still logging in but no longer booking is not healthy`() {
        // The case a login-only metric would call healthy: they open the app out of habit
        // and run the actual business somewhere else. This is the churn that surprises people.
        val score = calculator.score(
            inputs(daysSinceActivity = 1, recentMinutes = 200, priorMinutes = 900, recentReservations = 0)
        )

        assertTrue(score < 75, "score was $score")
    }

    @Test
    fun `a halving of engagement drags the score down even with recent logins`() {
        val steady = calculator.score(inputs(recentMinutes = 1_000, priorMinutes = 1_000))
        val declining = calculator.score(inputs(recentMinutes = 300, priorMinutes = 1_000))

        assertTrue(declining < steady, "$declining should be below $steady")
    }

    @Test
    fun `a brand new account is neutral, not critical`() {
        val score = calculator.score(
            inputs(
                accountAgeDays = 3, daysSinceActivity = 0, recentMinutes = 40,
                priorMinutes = 0, recentReservations = 0, usersActive14d = 1
            )
        )

        // Flagging every signup as at-risk is how a retention board gets ignored.
        assertEquals(ChurnRisk.WATCH, ChurnRisk.fromScore(score))
    }

    @Test
    fun `unused seats reduce the score`() {
        val full = calculator.score(inputs(usersTotal = 6, usersActive14d = 6))
        val mostlyIdle = calculator.score(inputs(usersTotal = 6, usersActive14d = 1))

        assertTrue(mostlyIdle < full, "paying for seats nobody uses precedes a downgrade")
    }

    @Test
    fun `errors we caused count against the score`() {
        val clean = calculator.score(inputs(errors14d = 0))
        val broken = calculator.score(inputs(errors14d = 500))

        assertEquals(10, clean - broken, "reliability contributes at most 10 points")
    }

    @Test
    fun `a growing studio with no prior baseline is credited, not penalised`() {
        val score = calculator.score(inputs(recentMinutes = 600, priorMinutes = 0, accountAgeDays = 30))

        assertTrue(score >= 75, "score was $score")
    }
}
