-- =============================================================================
-- DATA FIX: Restore is_all_day = TRUE for appointments corrupted by the edit bug
--
-- Root cause: AppointmentEditView.tsx hardcoded isAllDay: false in every PUT
-- payload, so any save of an all-day appointment silently cleared the flag.
-- The corrupted rows are identifiable by a precise pattern:
--   • is_all_day = FALSE
--   • start_date_time (in Europe/Warsaw local time) = 00:00 (local midnight)
--   • end_date_time   (in Europe/Warsaw local time) = 23:59
--   • both timestamps fall on the same calendar day (local time)
--   • row is not soft-deleted
--
-- HOW TO RUN
-- ----------
-- 1. Run the PREVIEW query (below) to see what will be changed.
-- 2. Open a psql session and run the whole file inside a transaction:
--      BEGIN;
--      \i db-fix-is-all-day.sql
--      -- inspect output, then either:
--      COMMIT;     -- to apply
--      -- or:
--      ROLLBACK;   -- to undo
-- =============================================================================

-- ─── 1. PREVIEW — see what would be updated ───────────────────────────────────

SELECT
    id,
    studio_id,
    start_date_time,
    end_date_time,
    (start_date_time AT TIME ZONE 'Europe/Warsaw')::time AS local_start_time,
    (end_date_time   AT TIME ZONE 'Europe/Warsaw')::time AS local_end_time,
    (start_date_time AT TIME ZONE 'Europe/Warsaw')::date AS local_date
FROM appointments
WHERE
    is_all_day  = FALSE
    AND deleted_at IS NULL
    -- start = local midnight  (00:00:00 in Warsaw time)
    AND EXTRACT(HOUR   FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
    AND EXTRACT(MINUTE FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
    AND EXTRACT(SECOND FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
    -- end = local 23:59  (minutes >= 59, hour = 23)
    AND EXTRACT(HOUR   FROM (end_date_time AT TIME ZONE 'Europe/Warsaw')) = 23
    AND EXTRACT(MINUTE FROM (end_date_time AT TIME ZONE 'Europe/Warsaw')) >= 59
    -- both on the same local calendar day
    AND (start_date_time AT TIME ZONE 'Europe/Warsaw')::date
      = (end_date_time   AT TIME ZONE 'Europe/Warsaw')::date
ORDER BY studio_id, start_date_time;


-- ─── 2. UPDATE — run inside BEGIN/COMMIT block ────────────────────────────────

UPDATE appointments
SET    is_all_day = TRUE
WHERE
    is_all_day  = FALSE
    AND deleted_at IS NULL
    AND EXTRACT(HOUR   FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
    AND EXTRACT(MINUTE FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
    AND EXTRACT(SECOND FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
    AND EXTRACT(HOUR   FROM (end_date_time AT TIME ZONE 'Europe/Warsaw')) = 23
    AND EXTRACT(MINUTE FROM (end_date_time AT TIME ZONE 'Europe/Warsaw')) >= 59
    AND (start_date_time AT TIME ZONE 'Europe/Warsaw')::date
      = (end_date_time   AT TIME ZONE 'Europe/Warsaw')::date;

-- Wyświetl ile wierszy zostało zaktualizowanych
-- psql wypisze: "UPDATE N"
