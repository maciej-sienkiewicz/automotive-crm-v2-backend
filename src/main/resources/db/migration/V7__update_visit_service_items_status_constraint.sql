-- Migration: Update visit_service_items status check constraint
-- Description: Allow PENDING, APPROVED, REJECTED, CONFIRMED statuses (remove IN_PROGRESS, COMPLETED)
-- Date: 2026-01-17

-- Drop old constraint if exists
ALTER TABLE visit_service_items
DROP CONSTRAINT IF EXISTS visit_service_items_status_check;

-- Add new constraint with updated status values
ALTER TABLE visit_service_items
ADD CONSTRAINT visit_service_items_status_check
CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CONFIRMED'));

-- Comment
COMMENT ON CONSTRAINT visit_service_items_status_check ON visit_service_items
IS 'Allowed service item statuses: PENDING, APPROVED, REJECTED, CONFIRMED';
