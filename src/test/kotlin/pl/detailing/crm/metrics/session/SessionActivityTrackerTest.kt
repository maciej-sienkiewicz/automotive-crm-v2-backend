package pl.detailing.crm.metrics.session

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ActorKind
import pl.detailing.crm.metrics.infrastructure.UserSessionEntity
import pl.detailing.crm.metrics.infrastructure.UserSessionRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Tests for the clamp — the single rule that makes "time spent in the CRM" mean anything.
 *
 * Every case below is a scenario that would silently corrupt the headline usage number
 * if the clamp were removed, which is why they are asserted rather than trusted.
 */
class SessionActivityTrackerTest {

    private val properties = MetricsProperties(
        session = MetricsProperties.SessionProperties(
            heartbeatIntervalSeconds = 60,
            maxCreditedGapSeconds = 90,
            timeoutSeconds = 300,
            minMeaningfulSeconds = 30
        )
    )

    private val repository = mockk<UserSessionRepository>(relaxed = true)
    private val tracker = SessionActivityTracker(repository, properties)

    private fun session(startedAt: Instant, lastActivityAt: Instant = startedAt) = UserSessionEntity(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        sessionKey = "k",
        actorKind = ActorKind.OWNER,
        roleLabel = "OWNER",
        startedAt = startedAt,
        lastActivityAt = lastActivityAt,
        sessionDate = LocalDate.of(2026, 8, 19)
    )

    @Test
    fun `a normal heartbeat credits the full elapsed interval`() {
        val start = Instant.parse("2026-08-19T08:00:00Z")
        val s = session(start)

        tracker.creditInterval(s, start.plusSeconds(60), active = true)

        assertEquals(60, s.activeSeconds)
        assertEquals(0, s.idleSeconds)
    }

    @Test
    fun `a laptop closed overnight credits 90 seconds, not 16 hours`() {
        val start = Instant.parse("2026-08-19T17:00:00Z")
        val s = session(start)

        // The scenario the whole module exists to defeat: one heartbeat, a 16-hour gap.
        tracker.creditInterval(s, start.plusSeconds(16 * 3600), active = true)

        assertEquals(90, s.activeSeconds, "gap must be clamped to max-credited-gap-seconds")
        // The rest is not discarded — it is booked as idle, so active + idle still
        // reconciles with the wall-clock span of the session.
        assertEquals(16 * 3600 - 90, s.idleSeconds)
    }

    @Test
    fun `an inactive heartbeat credits idle time, never active time`() {
        val start = Instant.parse("2026-08-19T08:00:00Z")
        val s = session(start)

        tracker.creditInterval(s, start.plusSeconds(60), active = false)

        assertEquals(0, s.activeSeconds)
        assertEquals(60, s.idleSeconds)
    }

    @Test
    fun `consecutive heartbeats accumulate without double counting`() {
        val start = Instant.parse("2026-08-19T08:00:00Z")
        val s = session(start)

        tracker.creditInterval(s, start.plusSeconds(60), active = true)
        tracker.creditInterval(s, start.plusSeconds(120), active = true)
        tracker.creditInterval(s, start.plusSeconds(180), active = true)

        // Each call advances lastActivityAt, so the deltas partition the elapsed time
        // instead of overlapping. This is what lets heartbeats and passive request
        // touches share one accumulator safely.
        assertEquals(180, s.activeSeconds)
    }

    @Test
    fun `a clock going backwards credits nothing`() {
        val start = Instant.parse("2026-08-19T08:00:00Z")
        val s = session(start, lastActivityAt = start.plusSeconds(120))

        tracker.creditInterval(s, start.plusSeconds(60), active = true)

        assertEquals(0, s.activeSeconds)
        assertEquals(0, s.idleSeconds)
    }

    @Test
    fun `a forgotten tab is not a meaningful session`() {
        val start = Instant.parse("2026-08-19T08:00:00Z")
        val s = session(start)
        // Hours of wall-clock presence, never touched by a human.
        s.activeSeconds = 5_000
        s.interactionCount = 0

        assertFalse(tracker.isMeaningful(s), "zero interactions can never be meaningful")
    }

    @Test
    fun `a five second visit is not a meaningful session`() {
        val s = session(Instant.parse("2026-08-19T08:00:00Z"))
        s.activeSeconds = 5
        s.interactionCount = 3

        assertFalse(tracker.isMeaningful(s))
    }

    @Test
    fun `real work counts as a meaningful session`() {
        val s = session(Instant.parse("2026-08-19T08:00:00Z"))
        s.activeSeconds = 900
        s.interactionCount = 42

        assertTrue(tracker.isMeaningful(s))
    }

    @Test
    fun `session keys are hashed, never the raw session id`() {
        val raw = "9A1F2C3D4E5F60718293A4B5C6D7E8F9"
        val hashed = tracker.hashSessionId(raw)

        assertEquals(64, hashed.length, "SHA-256 hex")
        assertFalse(hashed.contains(raw, ignoreCase = true))
        assertEquals(hashed, tracker.hashSessionId(raw), "hashing must be stable")
    }

    @Test
    fun `heartbeat on an unknown session does not throw`() {
        every { repository.findOpenBySessionKey(any()) } returns emptyList()
        every { repository.findById(any()) } returns java.util.Optional.empty()

        // A client beating after a backend restart must not produce a 500 in the browser.
        tracker.touchFromRequest(UUID.randomUUID(), UUID.randomUUID(), "unknown-session")
    }
}
