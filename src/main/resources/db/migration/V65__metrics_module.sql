-- ═══════════════════════════════════════════════════════════════════════════════
-- Metrics & product-analytics module (built from scratch).
--
-- Replaces the previous dashboarding approach, which lived entirely in a static
-- Grafana dashboard file (crm-overview.json) fed by Prometheus. That setup could
-- answer "is the server healthy right now" and nothing else: Prometheus keeps a
-- short retention window, cannot store a per-tenant identity without a cardinality
-- explosion, and has no notion of a business day. Questions such as "how many hours
-- did this studio's owner spend in the CRM last month", "which endpoints has nobody
-- called since March" or "which customers did yesterday's bug hit" were structurally
-- unanswerable there.
--
-- Prometheus is NOT removed — it keeps doing what it is good at (real-time technical
-- signals, alerting, JVM/HTTP internals). This module owns the long-horizon,
-- tenant-attributed product analytics that belong in a database.
--
-- Note on this project's schema management: spring.flyway.enabled is currently false
-- and Hibernate runs with ddl-auto=update, so the entity definitions create these
-- tables on boot. This migration is the reviewable, environment-independent record of
-- the same schema, and it carries the parts Hibernate cannot express — partial
-- indexes, the CHECK-free enum columns and the composite indexes the roll-ups need.
-- Every statement is idempotent so it is safe to apply to a database Hibernate has
-- already touched.
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── 1. Business event stream ──────────────────────────────────────────────────
-- Append-only ledger. Written asynchronously off the hot path, read almost only by
-- the nightly roll-up. Carries what leaves no other trace (SMS, e-mail); things that
-- own a table are counted from that table instead.

CREATE TABLE IF NOT EXISTS metric_events (
    id          uuid        PRIMARY KEY,
    studio_id   uuid,                       -- NULL only for pre-tenant events (failed login)
    user_id     uuid,
    actor_kind  varchar(20),                -- OWNER | EMPLOYEE, snapshotted at write time
    event_type  varchar(60) NOT NULL,
    quantity    bigint      NOT NULL DEFAULT 1,
    occurred_at timestamptz NOT NULL,
    event_date  date        NOT NULL,       -- Europe/Warsaw calendar day, materialised
    payload     jsonb
);

CREATE INDEX IF NOT EXISTS idx_metric_events_studio_date_type
    ON metric_events (studio_id, event_date, event_type);
CREATE INDEX IF NOT EXISTS idx_metric_events_date_type
    ON metric_events (event_date, event_type);
CREATE INDEX IF NOT EXISTS idx_metric_events_studio_user
    ON metric_events (studio_id, user_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_metric_events_occurred_at
    ON metric_events (occurred_at);

-- ── 2. Session tracking ───────────────────────────────────────────────────────
-- active_seconds holds ENGAGED time, credited only in clamped increments from
-- heartbeats that reported a visible tab with recent interaction. idle_seconds
-- absorbs everything else, so active + idle still reconciles with the wall-clock
-- span and an analyst can see how much of a session was dead time.

CREATE TABLE IF NOT EXISTS metric_user_sessions (
    id                uuid        PRIMARY KEY,
    studio_id         uuid        NOT NULL,
    user_id           uuid        NOT NULL,
    session_key       varchar(64) NOT NULL,   -- SHA-256 of JSESSIONID, never the raw value
    actor_kind        varchar(20) NOT NULL,   -- OWNER | EMPLOYEE
    role_label        varchar(100) NOT NULL,
    started_at        timestamptz NOT NULL,
    last_activity_at  timestamptz NOT NULL,
    ended_at          timestamptz,
    end_reason        varchar(30),            -- LOGOUT | CLIENT_CLOSED | TIMEOUT | REPLACED | SERVER_SHUTDOWN
    active_seconds    bigint      NOT NULL DEFAULT 0,
    idle_seconds      bigint      NOT NULL DEFAULT 0,
    interaction_count bigint      NOT NULL DEFAULT 0,
    request_count     bigint      NOT NULL DEFAULT 0,
    is_meaningful     boolean     NOT NULL DEFAULT false,
    session_date      date        NOT NULL,
    device            varchar(40),
    app_version       varchar(40),
    entry_route       varchar(200),
    last_route        varchar(200)
);

CREATE INDEX IF NOT EXISTS idx_metric_sessions_studio_date
    ON metric_user_sessions (studio_id, session_date);
CREATE INDEX IF NOT EXISTS idx_metric_sessions_studio_user
    ON metric_user_sessions (studio_id, user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_metric_sessions_session_key
    ON metric_user_sessions (session_key);

-- Partial: the sweeper only ever looks at open sessions, which are a handful of rows
-- out of a table that grows forever. A full index would be mostly dead weight.
CREATE INDEX IF NOT EXISTS idx_metric_sessions_open
    ON metric_user_sessions (last_activity_at)
    WHERE ended_at IS NULL;

-- Every time-spent aggregate filters on is_meaningful, so it belongs in the index
-- predicate rather than being re-evaluated per row on each report.
CREATE INDEX IF NOT EXISTS idx_metric_sessions_meaningful
    ON metric_user_sessions (session_date, studio_id, actor_kind)
    WHERE is_meaningful = true;

-- ── 3. API audit ──────────────────────────────────────────────────────────────
-- The catalog is seeded from Spring's routing table at every boot, so an endpoint
-- that has NEVER been called still has a row. That inversion — "what exists" LEFT
-- JOIN "what was called" — is what makes a dead-endpoint report possible at all.

CREATE TABLE IF NOT EXISTS metric_api_endpoints (
    id                  uuid         PRIMARY KEY,
    http_method         varchar(10)  NOT NULL,
    path_template       varchar(300) NOT NULL,
    controller          varchar(150) NOT NULL,
    handler             varchar(150) NOT NULL,
    module              varchar(60)  NOT NULL,
    is_active_in_code   boolean      NOT NULL DEFAULT true,
    requires_auth       boolean      NOT NULL DEFAULT true,
    first_seen_at       timestamptz  NOT NULL,
    last_seen_in_code_at timestamptz NOT NULL,
    last_called_at      timestamptz,
    total_calls         bigint       NOT NULL DEFAULT 0,
    is_retention_exempt boolean      NOT NULL DEFAULT false,
    exemption_note      varchar(300)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_api_endpoints_signature
    ON metric_api_endpoints (http_method, path_template);
CREATE INDEX IF NOT EXISTS idx_metric_api_endpoints_last_called
    ON metric_api_endpoints (last_called_at);
CREATE INDEX IF NOT EXISTS idx_metric_api_endpoints_module
    ON metric_api_endpoints (module);

-- Per-endpoint per-day counters, flushed from memory once a minute. Deliberately NOT
-- one row per request: that table would outgrow every business table in this database
-- within a week and answer no question this one cannot.
CREATE TABLE IF NOT EXISTS metric_api_endpoint_daily (
    id                uuid   PRIMARY KEY,
    endpoint_id       uuid   NOT NULL,
    usage_date        date   NOT NULL,
    call_count        bigint NOT NULL DEFAULT 0,
    error_count       bigint NOT NULL DEFAULT 0,
    total_duration_ms bigint NOT NULL DEFAULT 0,
    max_duration_ms   bigint NOT NULL DEFAULT 0,
    distinct_studios  int    NOT NULL DEFAULT 0
);

-- Required by the flush job's ON CONFLICT clause, which is what lets two app
-- instances sum their counters instead of overwriting each other on a rolling deploy.
CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_api_daily
    ON metric_api_endpoint_daily (endpoint_id, usage_date);
CREATE INDEX IF NOT EXISTS idx_metric_api_daily_date
    ON metric_api_endpoint_daily (usage_date);
CREATE INDEX IF NOT EXISTS idx_metric_api_daily_endpoint
    ON metric_api_endpoint_daily (endpoint_id, usage_date DESC);

-- Per-tenant traffic, latency and module adoption.
-- Grain includes `module` so the same rows answer both "is the CRM slow for THIS
-- customer" and "which modules does this customer actually open".
CREATE TABLE IF NOT EXISTS metric_studio_api_daily (
    id                 uuid        PRIMARY KEY,
    studio_id          uuid        NOT NULL,
    usage_date         date        NOT NULL,
    module             varchar(60) NOT NULL,
    call_count         bigint      NOT NULL DEFAULT 0,
    error_count        bigint      NOT NULL DEFAULT 0,
    total_duration_ms  bigint      NOT NULL DEFAULT 0,
    max_duration_ms    bigint      NOT NULL DEFAULT 0,
    distinct_endpoints int         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_studio_api_daily
    ON metric_studio_api_daily (studio_id, usage_date, module);
CREATE INDEX IF NOT EXISTS idx_metric_studio_api_daily_date
    ON metric_studio_api_daily (usage_date);

-- ── 4. Tenant-aware error tracking ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS metric_error_events (
    id              uuid         PRIMARY KEY,
    studio_id       uuid,                     -- NULL only before a tenant is resolved
    user_id         uuid,
    origin          varchar(20)  NOT NULL,    -- BACKEND | FRONTEND | SCHEDULED_JOB | INTEGRATION
    severity        varchar(20)  NOT NULL,    -- WARNING | ERROR | CRITICAL
    fingerprint     varchar(32)  NOT NULL,
    exception_class varchar(200) NOT NULL,
    message         varchar(1000),
    stack_trace     text,
    http_method     varchar(10),
    path            varchar(300),
    http_status     int,
    correlation_id  uuid,                     -- joins to X-Correlation-ID in the app logs
    occurred_at     timestamptz  NOT NULL,
    app_version     varchar(40),
    user_agent      varchar(300),
    context         jsonb
);

CREATE INDEX IF NOT EXISTS idx_metric_errors_studio_time
    ON metric_error_events (studio_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_metric_errors_fingerprint
    ON metric_error_events (fingerprint, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_metric_errors_occurred
    ON metric_error_events (occurred_at);
CREATE INDEX IF NOT EXISTS idx_metric_errors_correlation
    ON metric_error_events (correlation_id)
    WHERE correlation_id IS NOT NULL;

-- One row per distinct defect. Turns "4 812 errors" into "nine defects, this one hit
-- 23 studios" — the difference between a number and something actionable.
CREATE TABLE IF NOT EXISTS metric_error_groups (
    fingerprint         varchar(32)  PRIMARY KEY,
    origin              varchar(20)  NOT NULL,
    title               varchar(300) NOT NULL,
    exception_class     varchar(200) NOT NULL,
    severity            varchar(20)  NOT NULL,
    first_seen_at       timestamptz  NOT NULL,
    last_seen_at        timestamptz  NOT NULL,
    occurrence_count    bigint       NOT NULL DEFAULT 0,
    affected_studios    int          NOT NULL DEFAULT 0,
    status              varchar(20)  NOT NULL DEFAULT 'NEW',
    resolved_at         timestamptz,
    resolution_note     varchar(1000),
    resolved_in_version varchar(40)
);

CREATE INDEX IF NOT EXISTS idx_metric_error_groups_last_seen
    ON metric_error_groups (last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_metric_error_groups_status
    ON metric_error_groups (status, last_seen_at DESC);

-- Which tenants a defect touched. Derivable with GROUP BY over the occurrences, but
-- those are purged after 90 days and this aggregate is read on every console page —
-- so it is upserted and it outlives the raw rows.
CREATE TABLE IF NOT EXISTS metric_error_group_impacts (
    id             uuid        PRIMARY KEY,
    fingerprint    varchar(32) NOT NULL,
    studio_id      uuid        NOT NULL,
    occurrences    bigint      NOT NULL DEFAULT 0,
    first_seen_at  timestamptz NOT NULL,
    last_seen_at   timestamptz NOT NULL,
    affected_users int         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_error_impact
    ON metric_error_group_impacts (fingerprint, studio_id);
CREATE INDEX IF NOT EXISTS idx_metric_error_impact_studio
    ON metric_error_group_impacts (studio_id, last_seen_at DESC);

-- ── 5. Roll-ups (the read model) ──────────────────────────────────────────────
-- Every console screen reads these and only these. Plan and status are stored AS OF
-- that day, so a studio upgrading in June does not rewrite its own January history
-- the way a live join against the subscription tables would.

CREATE TABLE IF NOT EXISTS metric_daily_studio_snapshots (
    id                      uuid        PRIMARY KEY,
    studio_id               uuid        NOT NULL,
    snapshot_date           date        NOT NULL,
    plan_key                varchar(30) NOT NULL,
    subscription_status     varchar(20) NOT NULL,
    active_add_ons          int         NOT NULL DEFAULT 0,
    mrr_gross_cents         bigint      NOT NULL DEFAULT 0,
    users_total             int         NOT NULL DEFAULT 0,
    users_active            int         NOT NULL DEFAULT 0,
    sessions_count          int         NOT NULL DEFAULT 0,
    active_minutes_total    bigint      NOT NULL DEFAULT 0,
    active_minutes_owner    bigint      NOT NULL DEFAULT 0,
    active_minutes_employee bigint      NOT NULL DEFAULT 0,
    api_calls               bigint      NOT NULL DEFAULT 0,
    reservations_created    bigint      NOT NULL DEFAULT 0,
    visits_created          bigint      NOT NULL DEFAULT 0,
    visits_completed        bigint      NOT NULL DEFAULT 0,
    logins                  bigint      NOT NULL DEFAULT 0,
    sms_sent                bigint      NOT NULL DEFAULT 0,
    emails_sent             bigint      NOT NULL DEFAULT 0,
    sms_credits_remaining   int         NOT NULL DEFAULT 0,
    errors_total            bigint      NOT NULL DEFAULT 0,
    errors_critical         bigint      NOT NULL DEFAULT 0,
    avg_latency_ms          bigint      NOT NULL DEFAULT 0,
    health_score            int         NOT NULL DEFAULT 0,
    churn_risk              varchar(20) NOT NULL DEFAULT 'HEALTHY',
    last_activity_at        timestamptz,
    computed_at             timestamptz NOT NULL DEFAULT now()
);

-- Required by the roll-up's ON CONFLICT clause. That clause is what makes the job
-- idempotent: re-running a day recomputes it instead of doubling every figure on it.
CREATE UNIQUE INDEX IF NOT EXISTS uq_metric_studio_snapshot
    ON metric_daily_studio_snapshots (studio_id, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_metric_studio_snapshot_date
    ON metric_daily_studio_snapshots (snapshot_date);
CREATE INDEX IF NOT EXISTS idx_metric_studio_snapshot_studio
    ON metric_daily_studio_snapshots (studio_id, snapshot_date DESC);
CREATE INDEX IF NOT EXISTS idx_metric_studio_snapshot_plan
    ON metric_daily_studio_snapshots (snapshot_date, plan_key);
CREATE INDEX IF NOT EXISTS idx_metric_studio_snapshot_risk
    ON metric_daily_studio_snapshots (snapshot_date, churn_risk, mrr_gross_cents DESC);

CREATE TABLE IF NOT EXISTS metric_daily_platform_snapshots (
    snapshot_date        date        PRIMARY KEY,
    studios_total        int         NOT NULL DEFAULT 0,
    studios_paying       int         NOT NULL DEFAULT 0,
    studios_trialing     int         NOT NULL DEFAULT 0,
    studios_expired      int         NOT NULL DEFAULT 0,
    studios_plan_basic   int         NOT NULL DEFAULT 0,
    studios_plan_full    int         NOT NULL DEFAULT 0,
    new_signups          int         NOT NULL DEFAULT 0,
    churned              int         NOT NULL DEFAULT 0,
    mrr_gross_cents      bigint      NOT NULL DEFAULT 0,
    arpa_gross_cents     bigint      NOT NULL DEFAULT 0,
    dau_studios          int         NOT NULL DEFAULT 0,
    wau_studios          int         NOT NULL DEFAULT 0,
    mau_studios          int         NOT NULL DEFAULT 0,
    dau_users            int         NOT NULL DEFAULT 0,
    stickiness_permille  int         NOT NULL DEFAULT 0,
    active_minutes_total bigint      NOT NULL DEFAULT 0,
    reservations_created bigint      NOT NULL DEFAULT 0,
    visits_completed     bigint      NOT NULL DEFAULT 0,
    sms_sent             bigint      NOT NULL DEFAULT 0,
    emails_sent          bigint      NOT NULL DEFAULT 0,
    api_calls            bigint      NOT NULL DEFAULT 0,
    errors_total         bigint      NOT NULL DEFAULT 0,
    error_groups_new     int         NOT NULL DEFAULT 0,
    studios_with_errors  int         NOT NULL DEFAULT 0,
    computed_at          timestamptz NOT NULL DEFAULT now()
);

-- ── 6. Enum columns stay CHECK-free ───────────────────────────────────────────
-- Hibernate's schema export generates CHECK (col IN (...)) for @Enumerated columns
-- unless an explicit varchar columnDefinition is given (the entities do give one).
-- These DROPs cover databases where an older Hibernate already created them: a frozen
-- CHECK turns every new enum constant into an insert failure until somebody remembers
-- a migration — the exact trap V36__fix_all_enum_check_constraints was written to undo.

ALTER TABLE metric_events            DROP CONSTRAINT IF EXISTS metric_events_event_type_check;
ALTER TABLE metric_events            DROP CONSTRAINT IF EXISTS metric_events_actor_kind_check;
ALTER TABLE metric_user_sessions     DROP CONSTRAINT IF EXISTS metric_user_sessions_actor_kind_check;
ALTER TABLE metric_user_sessions     DROP CONSTRAINT IF EXISTS metric_user_sessions_end_reason_check;
ALTER TABLE metric_error_events      DROP CONSTRAINT IF EXISTS metric_error_events_origin_check;
ALTER TABLE metric_error_events      DROP CONSTRAINT IF EXISTS metric_error_events_severity_check;
ALTER TABLE metric_error_groups      DROP CONSTRAINT IF EXISTS metric_error_groups_origin_check;
ALTER TABLE metric_error_groups      DROP CONSTRAINT IF EXISTS metric_error_groups_severity_check;
ALTER TABLE metric_error_groups      DROP CONSTRAINT IF EXISTS metric_error_groups_status_check;

-- ── 7. Backfill ───────────────────────────────────────────────────────────────
-- Reservations, visits and logins are counted from their own tables, which already
-- hold the full history. Seeding empty snapshot rows for the last 90 days means the
-- console shows real trends from day one; the roll-up job (run via
-- POST /api/internal/metrics/recompute, or overnight) fills in the numbers.
-- Session time and error data legitimately start empty — nothing was measuring them
-- before this module existed, and inventing values would be worse than a visible gap.

INSERT INTO metric_daily_studio_snapshots (
    id, studio_id, snapshot_date, plan_key, subscription_status, computed_at
)
SELECT
    gen_random_uuid(),
    s.id,
    d::date,
    COALESCE((SELECT p.plan_key FROM studio_subscription_plans ssp
              JOIN subscription_plans p ON p.id = ssp.plan_id
              WHERE ssp.studio_id = s.id), 'NONE'),
    s.subscription_status,
    now()
FROM studios s
CROSS JOIN generate_series(
    (CURRENT_DATE - INTERVAL '90 days')::date,
    CURRENT_DATE,
    INTERVAL '1 day'
) AS d
-- Never before the studio existed: a row claiming zero activity for a studio that had
-- not signed up yet would drag every platform-wide average down for no reason.
WHERE d::date >= s.created_at::date
ON CONFLICT (studio_id, snapshot_date) DO NOTHING;
