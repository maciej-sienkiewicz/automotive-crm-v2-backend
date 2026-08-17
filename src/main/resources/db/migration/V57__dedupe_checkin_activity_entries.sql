-- Check-in used to log the same event twice: VISIT_CREATED (module VISIT) and
-- VISIT_ADDED (module VEHICLE), sharing one correlation_id. The feed filters by
-- context columns (vehicle_id/customer_id/visit_id), so the VEHICLE duplicate never
-- added information — remove the historical pairs. Standalone VISIT_ADDED rows
-- (if any exist without a paired VISIT_CREATED) are kept.
DELETE FROM audit_logs
WHERE action = 'VISIT_ADDED'
  AND correlation_id IS NOT NULL
  AND correlation_id IN (
      SELECT correlation_id FROM audit_logs
      WHERE action = 'VISIT_CREATED' AND correlation_id IS NOT NULL
  );
