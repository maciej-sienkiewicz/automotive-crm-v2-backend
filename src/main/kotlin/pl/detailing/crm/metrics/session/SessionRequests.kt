package pl.detailing.crm.metrics.session

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

/**
 * Client contract for session tracking.
 *
 * Note what the client is **not** allowed to send: elapsed time. It reports only whether
 * the interval was active and how many interactions happened; the server measures the
 * duration from its own clock. A client that could report its own durations could report
 * a studio into any usage figure it liked, and usage figures feed pricing conversations.
 */
data class StartSessionRequest(
    @field:Size(max = 40)
    val device: String? = null,

    @field:Size(max = 40)
    val appVersion: String? = null,

    @field:Size(max = 200)
    val route: String? = null
)

data class HeartbeatRequest(
    /**
     * True only when the tab was visible **and** the user interacted within the client's
     * idle threshold. The frontend contract is:
     *
     * ```js
     * const active = document.visibilityState === 'visible'
     *             && Date.now() - lastInteractionAt < IDLE_THRESHOLD_MS;
     * ```
     *
     * Sending `true` unconditionally would reintroduce exactly the phantom-usage problem
     * this module exists to eliminate.
     */
    val active: Boolean = true,

    /** Interactions since the previous heartbeat. Zero for several beats ⇒ user is away. */
    @field:Min(0)
    @field:Max(10_000)
    val interactions: Long = 0,

    /** Current SPA route, e.g. `/wizyty/kalendarz`. Feeds the "which screens matter" report. */
    @field:Size(max = 200)
    val route: String? = null
)

data class SessionSnapshotResponse(
    val sessionId: String,
    val activeSeconds: Long,
    val idleSeconds: Long,
    val interactionCount: Long,
    val meaningful: Boolean,
    /** Echoed so the client can align its timer without hard-coding the server's config. */
    val nextHeartbeatInSeconds: Long
)
