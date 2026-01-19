-- Migration: Add appointment color field to visits
-- Description: Adds appointment_color_id column to visits table to link visits with appointment colors
-- Date: 2026-01-19

-- Add appointment_color_id column to visits table
ALTER TABLE visits
    ADD COLUMN appointment_color_id UUID;

-- Create index for appointment_color_id
CREATE INDEX IF NOT EXISTS idx_visits_appointment_color_id
    ON visits(appointment_color_id);

-- Add comment for documentation
COMMENT ON COLUMN visits.appointment_color_id IS 'Reference to appointment color for calendar visualization';
