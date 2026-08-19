package pl.detailing.crm.metrics.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Every tunable of the metrics module in one place.
 *
 * The session thresholds in particular are product decisions, not implementation details:
 * they define what the company means by "a user spent 40 minutes in the CRM today", and
 * changing them changes every historical comparison. They belong in config, reviewed,
 * not scattered as magic numbers across three classes.
 */
@ConfigurationProperties(prefix = "crm.metrics")
data class MetricsProperties(

    /** Master switch. When false nothing is collected — useful for load tests and local dev. */
    val enabled: Boolean = true,

    val session: SessionProperties = SessionProperties(),
    val apiAudit: ApiAuditProperties = ApiAuditProperties(),
    val errors: ErrorProperties = ErrorProperties(),
    val ingest: IngestProperties = IngestProperties(),
    val retention: RetentionProperties = RetentionProperties(),

    /**
     * Shared secret required by every call under `/api/internal/metrics`, sent as
     * `X-Platform-Key`. Empty (the default) means the platform console is **closed**:
     * the endpoints answer 503 rather than serving cross-tenant data without a secret.
     * Set via env var `PLATFORM_METRICS_KEY`.
     */
    val platformApiKey: String = ""
) {

    data class SessionProperties(
        /** How often the browser is expected to report in, in seconds. */
        val heartbeatIntervalSeconds: Long = 60,

        /**
         * Hard cap on how much time a single heartbeat may add, in seconds.
         *
         * **This is the mechanism that eliminates "empty sessions".** A laptop lid closed
         * at 17:00 and reopened at 09:00 produces one heartbeat with a 16-hour gap; without
         * the cap that studio would look like the most engaged customer on the platform.
         * With it, the gap contributes at most [maxCreditedGapSeconds] and the sweeper has
         * already closed the session anyway.
         *
         * Must stay slightly above [heartbeatIntervalSeconds] to absorb network jitter.
         */
        val maxCreditedGapSeconds: Long = 90,

        /**
         * Silence after which the sweeper closes a session. The session is closed
         * *retroactively* at its last heartbeat, so the idle tail is never counted.
         */
        val timeoutSeconds: Long = 300,

        /**
         * Sessions shorter than this, or with zero interactions, are stored but flagged
         * `is_meaningful = false` and excluded from time-spent aggregates. Filters out
         * refreshes, health-check tabs and accidental double logins.
         */
        val minMeaningfulSeconds: Long = 30,

        /**
         * How long a request may substitute for a heartbeat. Lets the backend measure
         * sessions even if the frontend never implements heartbeats (fallback signal).
         */
        val requestTouchIntervalSeconds: Long = 30,

        /** Sweeper cron — every minute. */
        val sweeperCron: String = "0 * * * * *"
    )

    data class ApiAuditProperties(
        /** How often the in-memory usage counters are flushed to Postgres. */
        val flushIntervalMs: Long = 60_000,

        /** Safety valve: max distinct (endpoint, day) keys held in memory between flushes. */
        val maxBufferedKeys: Int = 20_000,

        /** Distinct-tenant counting per endpoint/day is capped to keep the buffer bounded. */
        val maxTrackedStudiosPerKey: Int = 512,

        /** Beyond this many days of silence an endpoint is reported DEAD. */
        val deadAfterDays: Long = 90,

        /** Beyond this many days of silence an endpoint is reported DORMANT. */
        val dormantAfterDays: Long = 30,

        /** Calls per 30 days below which an endpoint counts as LOW_TRAFFIC. */
        val lowTrafficThreshold: Long = 10,

        /**
         * A dead-endpoint report is only trustworthy once we have observed traffic for
         * at least this long. Below it every endpoint is reported INSUFFICIENT_DATA
         * instead of tempting somebody to delete a quarterly-report endpoint on day three.
         */
        val minObservationDays: Long = 30,

        /** Path prefixes never tracked (their own traffic would pollute the audit). */
        val ignoredPathPrefixes: List<String> = listOf("/actuator", "/api/health", "/api/v1/metrics")
    )

    data class ErrorProperties(
        /** Stack traces are truncated to this many characters before storage. */
        val maxStackTraceChars: Int = 8_000,

        /** Frames considered when computing a fingerprint. */
        val fingerprintFrames: Int = 5,

        /** Max frontend error reports accepted per session per minute. */
        val frontendRateLimitPerMinute: Int = 20,

        /** Package prefix treated as "our code" when picking fingerprint frames. */
        val applicationPackage: String = "pl.detailing.crm"
    )

    data class IngestProperties(
        /** Bounded queue between the hot path and the writer thread. */
        val queueCapacity: Int = 20_000,

        /** Rows per INSERT batch. */
        val batchSize: Int = 500,

        /** How often the queue is drained. */
        val flushIntervalMs: Long = 5_000
    )

    data class RetentionProperties(
        /** Raw business events. Daily snapshots keep the history beyond this. */
        val eventDays: Long = 120,

        /** Individual session rows. Aggregates survive in the daily snapshots. */
        val sessionDays: Long = 400,

        /** Individual error occurrences. Error *groups* are never purged automatically. */
        val errorEventDays: Long = 90,

        /** Per-endpoint per-day usage rows. */
        val apiUsageDays: Long = 400,

        /** Retention job cron — 03:40 every night, after the roll-ups. */
        val cron: String = "0 40 3 * * *"
    )
}
