-- Migration: Add CONVERTED status to appointments
-- Description: Allow appointments to be marked as CONVERTED when turned into visits
-- Date: 2026-01-16

-- Drop old constraint
ALTER TABLE appointments
DROP CONSTRAINT IF EXISTS appointments_status_check;

-- Add new constraint with CONVERTED status
ALTER TABLE appointments
ADD CONSTRAINT appointments_status_check
CHECK (status IN ('CREATED', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'CONVERTED'));

-- Comment
COMMENT ON CONSTRAINT appointments_status_check ON appointments
IS 'Allowed appointment statuses: CREATED, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, CONVERTED';
