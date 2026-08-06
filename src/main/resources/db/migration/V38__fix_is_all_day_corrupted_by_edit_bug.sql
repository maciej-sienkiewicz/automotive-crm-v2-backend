-- Fix appointments where is_all_day was silently overwritten to FALSE.
--
-- Root cause: AppointmentEditView.tsx hardcoded `isAllDay: false` in every PUT
-- payload. Any save of an all-day appointment via the edit form cleared the flag
-- while leaving the timestamps unchanged (local midnight → 22:00 UTC in Europe/Warsaw,
-- local 23:59 → 21:59 UTC). The corrupted rows are identified by that exact signature.

UPDATE appointments
SET    is_all_day = TRUE
WHERE  is_all_day  = FALSE
  AND  deleted_at  IS NULL
  -- start_date_time is local midnight (00:00:00 Europe/Warsaw)
  AND  EXTRACT(HOUR   FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
  AND  EXTRACT(MINUTE FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
  AND  EXTRACT(SECOND FROM (start_date_time AT TIME ZONE 'Europe/Warsaw')) = 0
  -- end_date_time is local 23:59 (Europe/Warsaw)
  AND  EXTRACT(HOUR   FROM (end_date_time AT TIME ZONE 'Europe/Warsaw')) = 23
  AND  EXTRACT(MINUTE FROM (end_date_time AT TIME ZONE 'Europe/Warsaw')) >= 59
  -- both on the same local calendar day
  AND  (start_date_time AT TIME ZONE 'Europe/Warsaw')::date
     = (end_date_time   AT TIME ZONE 'Europe/Warsaw')::date;
