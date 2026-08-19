package pl.detailing.crm.metrics.apiaudit

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.EndpointVitality
import pl.detailing.crm.metrics.infrastructure.ApiEndpointRepository
import pl.detailing.crm.metrics.query.GetDeadEndpointsHandler
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The classification rule behind the dead-endpoint report. Getting this wrong in either
 * direction has a real cost: too eager and somebody deletes a working endpoint, too shy
 * and the report never justifies the cleanup it exists to enable.
 */
class EndpointVitalityTest {

    private val handler = GetDeadEndpointsHandler(
        mockk<NamedParameterJdbcTemplate>(relaxed = true),
        mockk<ApiEndpointRepository>(relaxed = true),
        MetricsProperties(
            apiAudit = MetricsProperties.ApiAuditProperties(
                deadAfterDays = 90,
                dormantAfterDays = 30,
                lowTrafficThreshold = 10,
                minObservationDays = 30
            )
        )
    )

    private fun daysAgo(n: Long): Instant = Instant.now().minus(n, ChronoUnit.DAYS)

    @Test
    fun `nothing is called dead before the observation window closes`() {
        val vitality = handler.classify(
            reliable = false, lastCalled = null, daysSinceLastCall = null,
            calls30d = 0, callsTotal = 0
        )

        // A quarterly-report endpoint looks identical to a dead one after three days.
        assertEquals(EndpointVitality.INSUFFICIENT_DATA, vitality)
    }

    @Test
    fun `an endpoint never called since measurement began is its own category`() {
        val vitality = handler.classify(
            reliable = true, lastCalled = null, daysSinceLastCall = null,
            calls30d = 0, callsTotal = 0
        )

        assertEquals(EndpointVitality.NEVER_CALLED, vitality)
    }

    @Test
    fun `silence beyond the dead threshold is DEAD`() {
        val vitality = handler.classify(
            reliable = true, lastCalled = daysAgo(120), daysSinceLastCall = 120,
            calls30d = 0, callsTotal = 4_000
        )

        assertEquals(EndpointVitality.DEAD, vitality)
    }

    @Test
    fun `silence past a month but under the dead threshold is DORMANT`() {
        val vitality = handler.classify(
            reliable = true, lastCalled = daysAgo(45), daysSinceLastCall = 45,
            calls30d = 0, callsTotal = 900
        )

        assertEquals(EndpointVitality.DORMANT, vitality)
    }

    @Test
    fun `a handful of recent calls is LOW_TRAFFIC, not dead`() {
        val vitality = handler.classify(
            reliable = true, lastCalled = daysAgo(2), daysSinceLastCall = 2,
            calls30d = 4, callsTotal = 60
        )

        assertEquals(EndpointVitality.LOW_TRAFFIC, vitality)
    }

    @Test
    fun `normal traffic is ACTIVE`() {
        val vitality = handler.classify(
            reliable = true, lastCalled = daysAgo(0), daysSinceLastCall = 0,
            calls30d = 12_000, callsTotal = 400_000
        )

        assertEquals(EndpointVitality.ACTIVE, vitality)
    }
}
