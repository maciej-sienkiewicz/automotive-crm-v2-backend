package pl.detailing.crm.metrics.session

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ActorKind
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.domain.SessionEndReason
import pl.detailing.crm.metrics.domain.UserSessionId
import pl.detailing.crm.metrics.infrastructure.UserSessionEntity
import pl.detailing.crm.metrics.infrastructure.UserSessionRepository
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Measures how much time a user actually spends working in the CRM.
 *
 * ## The problem this solves
 *
 * The naive implementation — `ended_at - started_at` — measures how long a browser tab
 * existed, not how long anyone used it. On a real CRM most tabs are opened at 8:00 and
 * closed the next morning, so the naive number reports every studio as using the product
 * 14 hours a day. It is the single most common way a SaaS usage dashboard ends up
 * confidently wrong, and it is wrong in the flattering direction, which is worse.
 *
 * ## The mechanism
 *
 * Time is only ever credited in **clamped increments**, never as a difference between
 * two distant timestamps:
 *
 * ```
 * credited = min(now - lastActivityAt, maxCreditedGapSeconds)
 * ```
 *
 * with the increment counted as *active* only when the client reports the tab was
 * visible and the user interacted recently, and as *idle* otherwise. Three consequences:
 *
 * - A closed laptop reopened 16 hours later credits at most 90 seconds, not 16 hours.
 * - A missed heartbeat (flaky wifi) costs at most one interval, not the whole session.
 * - No client can inflate its own numbers: the server computes the delta from its own
 *   clock, and the client's only influence is the boolean "was this interval active".
 *
 * The tail is handled by [SessionSweeper], which closes silent sessions **retroactively
 * at their last heartbeat** — so the gap between "user walked away" and "sweeper noticed"
 * is never counted either.
 *
 * ## Owner vs employee
 *
 * [ActorKind] is snapshotted onto the session row at start from the authenticated
 * principal. Denormalised on purpose: promoting an employee to owner in March must not
 * silently rewrite February's owner/employee split.
 */
@Service
class SessionActivityTracker(
    private val repository: UserSessionRepository,
    private val properties: MetricsProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * sessionKey → open session id. Saves a SELECT on every heartbeat and every tracked
     * request. Purely a cache: a miss falls back to the database, so a restart or an
     * eviction costs one query, never a lost session.
     */
    private val openSessionCache = ConcurrentHashMap<String, UUID>()

    /** Cap so a long-running instance cannot accumulate keys for sessions that never closed. */
    private val maxCacheEntries = 20_000

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Opens (or returns the already-open) session for this browser session.
     * Idempotent: a page refresh calls it again and must not fragment the measurement
     * into two sessions, which would double-count the start overhead and break the
     * "sessions per day" metric.
     */
    @Transactional
    fun startSession(
        principal: UserPrincipal,
        httpSessionId: String,
        device: String?,
        appVersion: String?,
        entryRoute: String?
    ): UserSessionId {
        val sessionKey = hashSessionId(httpSessionId)

        repository.findOpenBySessionKey(sessionKey).firstOrNull()?.let {
            cachePut(sessionKey, it.id)
            return UserSessionId(it.id)
        }

        // Spring Security allows one concurrent session per user; if an older session for
        // this user is still open it belongs to a device that was signed out, and leaving
        // it open would keep collecting phantom idle time against that studio.
        repository.findOpenForUser(principal.studioId.value, principal.userId.value).forEach {
            closeRetroactively(it, SessionEndReason.REPLACED)
        }

        val now = Instant.now()
        val entity = UserSessionEntity(
            id = UUID.randomUUID(),
            studioId = principal.studioId.value,
            userId = principal.userId.value,
            sessionKey = sessionKey,
            actorKind = if (principal.isOwner) ActorKind.OWNER else ActorKind.EMPLOYEE,
            roleLabel = if (principal.isOwner) "OWNER" else "EMPLOYEE",
            startedAt = now,
            lastActivityAt = now,
            sessionDate = MetricsClock.dateOf(now),
            device = device?.take(40),
            appVersion = appVersion?.take(40),
            entryRoute = entryRoute?.take(200),
            lastRoute = entryRoute?.take(200)
        )

        repository.save(entity)
        cachePut(sessionKey, entity.id)
        return UserSessionId(entity.id)
    }

    /**
     * Credits one interval of time to the session.
     *
     * @param active whether the client reported the tab visible **and** the user
     *        interacting within the idle threshold. False credits idle time instead.
     * @param interactions clicks / keystrokes / route changes since the previous beat.
     *        A session that never reports one is never counted as meaningful, no matter
     *        how long it stayed open.
     */
    @Transactional
    fun heartbeat(
        principal: UserPrincipal,
        httpSessionId: String,
        active: Boolean,
        interactions: Long,
        route: String?
    ): SessionSnapshot? {
        val session = resolveOpenSession(principal, httpSessionId) ?: return null
        val now = Instant.now()

        creditInterval(session, now, active)
        session.interactionCount += interactions.coerceIn(0, 10_000)
        route?.let { session.lastRoute = it.take(200) }

        repository.save(session)
        return snapshotOf(session)
    }

    /**
     * Passive signal from the API-usage interceptor: an authenticated request proves the
     * user was there, even if the frontend never implements heartbeats.
     *
     * Rate-limited by `requestTouchIntervalSeconds` so a screen firing twelve parallel
     * requests does not do twelve UPDATEs, and credited through the exact same clamped
     * path as a heartbeat so heartbeats and requests can never double-count: whichever
     * arrives first moves `lastActivityAt`, and the other then sees a near-zero delta.
     */
    @Transactional
    fun touchFromRequest(studioId: UUID, userId: UUID, httpSessionId: String) {
        if (!properties.enabled) return

        try {
            val sessionKey = hashSessionId(httpSessionId)
            val sessionId = openSessionCache[sessionKey] ?: return
            val session = repository.findById(sessionId).orElse(null) ?: return
            if (!session.isOpen || session.studioId != studioId || session.userId != userId) return

            val now = Instant.now()
            val sinceLast = Duration.between(session.lastActivityAt, now).seconds
            session.requestCount += 1

            if (sinceLast >= properties.session.requestTouchIntervalSeconds) {
                creditInterval(session, now, active = true)
                repository.save(session)
            } else {
                // Still persist the request counter, but only once per touch interval —
                // the counter is a sanity check, not worth an UPDATE per request.
                if (session.requestCount % 20 == 0L) repository.save(session)
            }
        } catch (ex: Exception) {
            log.debug("Nie udało się odświeżyć sesji metryk z żądania: {}", ex.message)
        }
    }

    /** Explicit close: logout, or the client's `pagehide` beacon. */
    @Transactional
    fun endSession(httpSessionId: String, reason: SessionEndReason): SessionSnapshot? {
        val sessionKey = hashSessionId(httpSessionId)
        val session = repository.findOpenBySessionKey(sessionKey).firstOrNull() ?: return null

        val now = Instant.now()
        // A clean close still goes through the clamp: a beacon fired 40 minutes after the
        // last heartbeat means the user left 40 minutes ago, not that they worked through it.
        creditInterval(session, now, active = true)
        finalise(session, session.lastActivityAt, reason)

        repository.save(session)
        openSessionCache.remove(sessionKey)
        return snapshotOf(session)
    }

    /**
     * Used by the sweeper. Closes the session at its **last activity**, not at "now",
     * so the silence between the user walking away and the sweep is never counted.
     */
    @Transactional
    fun closeRetroactively(session: UserSessionEntity, reason: SessionEndReason) {
        finalise(session, session.lastActivityAt, reason)
        repository.save(session)
        openSessionCache.remove(session.sessionKey)
    }

    // ── Core accounting ──────────────────────────────────────────────────────

    /**
     * The clamp. Everything above funnels through here, which is why "time spent" cannot
     * be inflated by any single code path forgetting the rule.
     */
    internal fun creditInterval(session: UserSessionEntity, now: Instant, active: Boolean) {
        val elapsed = Duration.between(session.lastActivityAt, now).seconds
        if (elapsed <= 0) return

        val credited = min(elapsed, properties.session.maxCreditedGapSeconds)

        if (active) {
            session.activeSeconds += credited
        } else {
            session.idleSeconds += credited
        }

        // Time beyond the clamp is not lost — it is recorded as idle, so a session's
        // wall-clock span still reconciles with active + idle and an analyst comparing
        // the two can see exactly how much of the day was genuinely dead time.
        val overflow = elapsed - credited
        if (overflow > 0) session.idleSeconds += overflow

        session.lastActivityAt = now
    }

    private fun finalise(session: UserSessionEntity, endedAt: Instant, reason: SessionEndReason) {
        if (!session.isOpen) return
        session.endedAt = endedAt
        session.endReason = reason
        session.isMeaningful = isMeaningful(session)
    }

    /**
     * The "empty session" filter. Both conditions are required on purpose:
     * a long session with zero interactions is a forgotten tab, and a session with
     * interactions but three seconds of engaged time is a misclick on the bookmark.
     */
    internal fun isMeaningful(session: UserSessionEntity): Boolean =
        session.activeSeconds >= properties.session.minMeaningfulSeconds &&
            session.interactionCount > 0

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun resolveOpenSession(principal: UserPrincipal, httpSessionId: String): UserSessionEntity? {
        val sessionKey = hashSessionId(httpSessionId)

        openSessionCache[sessionKey]?.let { id ->
            val cached = repository.findById(id).orElse(null)
            if (cached != null && cached.isOpen) return cached
            openSessionCache.remove(sessionKey)
        }

        val found = repository.findOpenBySessionKey(sessionKey).firstOrNull()
        if (found != null) {
            cachePut(sessionKey, found.id)
            return found
        }

        // A heartbeat with no open session means the client survived a backend restart
        // (or started beating before calling /start). Opening one here keeps the
        // measurement continuous instead of silently dropping the rest of the workday.
        return repository.findById(
            startSession(principal, httpSessionId, null, null, null).value
        ).orElse(null)
    }

    private fun cachePut(sessionKey: String, id: UUID) {
        if (openSessionCache.size >= maxCacheEntries) openSessionCache.clear()
        openSessionCache[sessionKey] = id
    }

    /**
     * Sessions are keyed by a hash, never the raw `JSESSIONID`. A metrics export, a
     * support screenshot or a leaked backup of this table must not hand anybody a live
     * session cookie for a customer's account.
     */
    internal fun hashSessionId(httpSessionId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(httpSessionId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun snapshotOf(session: UserSessionEntity) = SessionSnapshot(
        sessionId = UserSessionId(session.id),
        activeSeconds = session.activeSeconds,
        idleSeconds = session.idleSeconds,
        interactionCount = session.interactionCount,
        meaningful = isMeaningful(session)
    )

    /** Cache size, exposed so the console can see the tracker is not leaking keys. */
    fun trackedOpenSessions(): Int = openSessionCache.size
}

data class SessionSnapshot(
    val sessionId: UserSessionId,
    val activeSeconds: Long,
    val idleSeconds: Long,
    val interactionCount: Long,
    val meaningful: Boolean
)
