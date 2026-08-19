package pl.detailing.crm.metrics.domain

/**
 * How a user session ended. The reason is what separates real usage from a browser tab
 * someone left open overnight — see [pl.detailing.crm.metrics.session.SessionActivityTracker].
 */
enum class SessionEndReason {
    /** User pressed "Wyloguj" — the only unambiguous end of a session. */
    LOGOUT,

    /** Browser fired `pagehide`/`beforeunload` and the client sent a closing beacon. */
    CLIENT_CLOSED,

    /** No heartbeat within the configured timeout: the sweeper closed it retroactively. */
    TIMEOUT,

    /** A newer session for the same user replaced this one (Spring Security allows one). */
    REPLACED,

    /** Application shut down with the session still open. */
    SERVER_SHUTDOWN
}

/** Which side of the stack produced an error. */
enum class ErrorOrigin {
    BACKEND,
    FRONTEND,
    SCHEDULED_JOB,
    INTEGRATION
}

/**
 * Severity of a tracked error. Deliberately coarse: three levels people actually act on,
 * rather than five nobody can tell apart.
 */
enum class ErrorSeverity {
    /** Expected, user-caused (validation, 404). Recorded only in aggregate, never alerts. */
    WARNING,

    /** Unhandled failure affecting one operation of one tenant. */
    ERROR,

    /** Failure affecting many tenants, data integrity, or money. Wakes somebody up. */
    CRITICAL
}

/** Triage state of an error group. */
enum class ErrorGroupStatus {
    NEW,
    ACKNOWLEDGED,
    RESOLVED,
    IGNORED
}

/**
 * How alive an API endpoint is, derived from the last time it was called.
 *
 * [NEVER_CALLED] is deliberately distinct from [DEAD]: an endpoint that has never been
 * called since we started measuring is a far stronger deletion candidate than one that
 * simply went quiet, and conflating the two costs the removal decision its evidence.
 */
enum class EndpointVitality(val label: String) {
    ACTIVE("Aktywny"),
    LOW_TRAFFIC("Niski ruch"),
    DORMANT("Uśpiony"),
    DEAD("Martwy"),
    NEVER_CALLED("Nigdy nie wywołany"),
    INSUFFICIENT_DATA("Za krótki okres obserwacji")
}

/** Retention / churn risk band derived from the tenant health score. */
enum class ChurnRisk(val label: String) {
    HEALTHY("Zdrowy"),
    WATCH("Do obserwacji"),
    AT_RISK("Zagrożony"),
    CRITICAL("Krytyczny");

    companion object {
        fun fromScore(score: Int): ChurnRisk = when {
            score >= 75 -> HEALTHY
            score >= 50 -> WATCH
            score >= 25 -> AT_RISK
            else -> CRITICAL
        }
    }
}

/** Distinguishes the studio owner from a hired employee in every usage aggregate. */
enum class ActorKind {
    OWNER,
    EMPLOYEE
}
