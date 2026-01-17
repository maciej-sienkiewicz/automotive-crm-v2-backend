-- Migration: Remove fuel_level and is_very_dirty columns from visits table
-- Description: Clean up unnecessary fields that are no longer needed
-- Date: 2026-01-17

-- Remove fuel_level column
ALTER TABLE visits DROP COLUMN IF EXISTS fuel_level;

-- Remove is_very_dirty column
ALTER TABLE visits DROP COLUMN IF EXISTS is_very_dirty;
