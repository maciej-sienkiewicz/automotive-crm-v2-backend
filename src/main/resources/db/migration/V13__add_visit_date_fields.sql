-- Add new date fields to visits table
-- V13__add_visit_date_fields.sql

-- Add estimated_completion_date column (set when visit is scheduled)
ALTER TABLE visits
    ADD COLUMN estimated_completion_date TIMESTAMP WITH TIME ZONE;

-- Add actual_completion_date column (set when status changes to READY_FOR_PICKUP)
ALTER TABLE visits
    ADD COLUMN actual_completion_date TIMESTAMP WITH TIME ZONE;

-- Rename completed_date to pickup_date (set when status changes to COMPLETED)
ALTER TABLE visits
    RENAME COLUMN completed_date TO pickup_date;

-- Add comments for clarity
COMMENT ON COLUMN visits.scheduled_date IS 'Start date - set when visit is created/scheduled';
COMMENT ON COLUMN visits.estimated_completion_date IS 'Estimated completion date - set when visit is scheduled';
COMMENT ON COLUMN visits.actual_completion_date IS 'Actual completion date - set when status changes to READY_FOR_PICKUP';
COMMENT ON COLUMN visits.pickup_date IS 'Pickup date - set when status changes to COMPLETED';
