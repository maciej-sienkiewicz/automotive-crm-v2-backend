-- Migration: Update visit service item status comment
-- Description: Update allowed statuses to PENDING, APPROVED, REJECTED, CONFIRMED
-- Date: 2026-01-17

-- Update comment for visit_service_items.status column
COMMENT ON COLUMN visit_service_items.status IS 'PENDING, APPROVED, REJECTED, or CONFIRMED (default for initial services)';
