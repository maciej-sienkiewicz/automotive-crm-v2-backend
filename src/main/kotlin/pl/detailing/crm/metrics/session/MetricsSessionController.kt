package pl.detailing.crm.metrics.session

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.SessionEndReason

/**
 * Tenant-facing session-tracking surface. Every call is scoped to the caller's own
 * session and studio — there is no path here that can read or write another tenant's data.
 *
 * Frontend integration (three calls, all fire-and-forget):
 *
 * ```js
 * await api.post('/api/v1/metrics/session/start', { device, appVersion, route });
 *
 * setInterval(() => {
 *   const active = document.visibilityState === 'visible'
 *               && Date.now() - lastInteractionAt < 120_000;
 *   api.post('/api/v1/metrics/session/heartbeat', { active, interactions, route });
 *   interactions = 0;
 * }, 60_000);
 *
 * addEventListener('pagehide', () =>
 *   navigator.sendBeacon('/api/v1/metrics/session/end'));
 * ```
 *
 * `sendBeacon` rather than `fetch` on unload: the browser guarantees delivery of a beacon
 * during page teardown and cancels in-flight fetches, so a plain fetch loses precisely the
 * close events that keep the numbers honest. And even if the beacon is lost, the sweeper
 * closes the session retroactively — the client is an optimisation, never a dependency.
 */
@RestController
@RequestMapping("/api/v1/metrics/session")
class MetricsSessionController(
    private val tracker: SessionActivityTracker,
    private val properties: MetricsProperties
) {

    @PostMapping("/start")
    fun start(
        @Valid @RequestBody(required = false) body: StartSessionRequest?,
        request: HttpServletRequest
    ): ResponseEntity<SessionSnapshotResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val sessionId = tracker.startSession(
            principal = principal,
            httpSessionId = requireSessionId(request),
            device = body?.device,
            appVersion = body?.appVersion,
            entryRoute = body?.route
        )

        return ResponseEntity.ok(
            SessionSnapshotResponse(
                sessionId = sessionId.toString(),
                activeSeconds = 0,
                idleSeconds = 0,
                interactionCount = 0,
                meaningful = false,
                nextHeartbeatInSeconds = properties.session.heartbeatIntervalSeconds
            )
        )
    }

    @PostMapping("/heartbeat")
    fun heartbeat(
        @Valid @RequestBody(required = false) body: HeartbeatRequest?,
        request: HttpServletRequest
    ): ResponseEntity<SessionSnapshotResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val beat = body ?: HeartbeatRequest()

        val snapshot = tracker.heartbeat(
            principal = principal,
            httpSessionId = requireSessionId(request),
            active = beat.active,
            interactions = beat.interactions,
            route = beat.route
        ) ?: return ResponseEntity.status(HttpStatus.NO_CONTENT).build()

        return ResponseEntity.ok(
            SessionSnapshotResponse(
                sessionId = snapshot.sessionId.toString(),
                activeSeconds = snapshot.activeSeconds,
                idleSeconds = snapshot.idleSeconds,
                interactionCount = snapshot.interactionCount,
                meaningful = snapshot.meaningful,
                nextHeartbeatInSeconds = properties.session.heartbeatIntervalSeconds
            )
        )
    }

    /**
     * Closes the session. Returns 204 with no body so it is a valid `sendBeacon` target
     * and costs the unloading page nothing.
     */
    @PostMapping("/end")
    fun end(request: HttpServletRequest): ResponseEntity<Void> {
        tracker.endSession(requireSessionId(request), SessionEndReason.CLIENT_CLOSED)
        return ResponseEntity.noContent().build()
    }

    private fun requireSessionId(request: HttpServletRequest): String =
        request.getSession(false)?.id
            ?: request.requestedSessionId
            ?: throw IllegalStateException("Brak sesji HTTP dla uwierzytelnionego żądania")
}
