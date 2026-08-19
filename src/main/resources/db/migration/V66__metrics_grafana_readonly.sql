-- ═══════════════════════════════════════════════════════════════════════════════
-- Read-only database role for Grafana.
--
-- The metrics module deliberately stores product analytics in Postgres rather than
-- Prometheus (tenant identity as a label would explode cardinality, and the retention
-- needed here is years, not days). The consequence — which was not spelled out when the
-- module shipped — is that Grafana cannot see any of it through the Prometheus
-- datasource. This role is what lets it.
--
-- ## Principle: Grafana gets the metrics, never the customers
--
-- The grants below are enumerated table by table, on purpose. A blanket
-- `GRANT SELECT ON ALL TABLES` would hand a dashboarding tool — reachable from a browser,
-- with anonymous viewer access enabled in this deployment — every customer name, phone
-- number, invoice and signed protocol in the platform. The role can read the metric_*
-- tables and one narrow view carrying nothing but a studio's id and display name, which
-- is the minimum needed to render "which studio" on a chart.
--
-- ## Applying this
--
-- The role is created WITHOUT a password. Setting one is an operations step, not a
-- migration step — a credential committed to a repository is a credential that has
-- leaked. After applying:
--
--     ALTER ROLE grafana_ro WITH PASSWORD '<generated>';
--
-- and put the same value in the deployment's GRAFANA_DB_PASSWORD.
--
-- ## Adding a metric table later
--
-- New tables are NOT covered automatically. That is deliberate: ALTER DEFAULT PRIVILEGES
-- would also cover every future business table created by the application user, which is
-- exactly the blanket grant this file avoids. Add an explicit GRANT here instead — the
-- one-line cost of a review is the point.
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── Role ──────────────────────────────────────────────────────────────────────

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
        -- LOGIN with no password: cannot actually connect until an operator sets one,
        -- so a half-applied migration leaves no reachable account behind.
        CREATE ROLE grafana_ro WITH LOGIN;
    END IF;
END
$$;

-- Connect + schema visibility only. No CREATE, no TEMP.
-- GRANT ... ON DATABASE needs a literal identifier, so the database name is interpolated
-- through format(%I) rather than written as CURRENT_CATALOG, which Postgres rejects there.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_ro', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO grafana_ro;
REVOKE CREATE ON SCHEMA public FROM grafana_ro;

-- ── Studio directory ──────────────────────────────────────────────────────────
-- A chart needs a studio's name; it does not need its billing status, e-mail alias or
-- trial dates. The view is the whole of what Grafana may know about a tenant.

CREATE OR REPLACE VIEW metric_studio_directory AS
SELECT s.id, s.name
FROM studios s;

GRANT SELECT ON metric_studio_directory TO grafana_ro;

-- ── Metric tables ─────────────────────────────────────────────────────────────

GRANT SELECT ON metric_daily_studio_snapshots   TO grafana_ro;
GRANT SELECT ON metric_daily_platform_snapshots TO grafana_ro;
GRANT SELECT ON metric_user_sessions            TO grafana_ro;
GRANT SELECT ON metric_events                   TO grafana_ro;
GRANT SELECT ON metric_api_endpoints            TO grafana_ro;
GRANT SELECT ON metric_api_endpoint_daily       TO grafana_ro;
GRANT SELECT ON metric_studio_api_daily         TO grafana_ro;
GRANT SELECT ON metric_error_groups             TO grafana_ro;
GRANT SELECT ON metric_error_group_impacts      TO grafana_ro;

-- Deliberately NOT granted: metric_error_events.
-- Individual occurrences carry request paths, user ids and stack traces, which can quote
-- customer data in an exception message. The grouped tables above answer every question a
-- dashboard asks ("which defect, how often, which studios"); the stack traces stay behind
-- the authenticated /api/internal/metrics/errors endpoint where they are needed to debug.

-- ── Endpoint usage view ───────────────────────────────────────────────────────
-- Facts only — days of silence and recent volume. The DEAD / DORMANT / NEVER_CALLED
-- classification is NOT reproduced here: it depends on the observation-window guard that
-- keeps the report from calling a quarterly endpoint dead on day three, and a second copy
-- of that rule in SQL would drift from the one in GetDeadEndpointsHandler. Grafana ranks
-- by silence; the API remains the authority on what may actually be deleted.

CREATE OR REPLACE VIEW metric_endpoint_usage AS
SELECT
    e.id,
    e.http_method,
    e.path_template,
    e.controller,
    e.module,
    e.requires_auth,
    e.is_retention_exempt,
    e.first_seen_at,
    e.last_called_at,
    e.total_calls,
    CASE WHEN e.last_called_at IS NULL THEN NULL
         ELSE EXTRACT(DAY FROM (now() - e.last_called_at))::int
    END AS days_silent,
    COALESCE((SELECT SUM(d.call_count) FROM metric_api_endpoint_daily d
              WHERE d.endpoint_id = e.id
                AND d.usage_date >= CURRENT_DATE - 30), 0) AS calls_30d,
    COALESCE((SELECT MAX(d.distinct_studios) FROM metric_api_endpoint_daily d
              WHERE d.endpoint_id = e.id
                AND d.usage_date >= CURRENT_DATE - 30), 0) AS studios_30d
FROM metric_api_endpoints e
WHERE e.is_active_in_code = true;

GRANT SELECT ON metric_endpoint_usage TO grafana_ro;
