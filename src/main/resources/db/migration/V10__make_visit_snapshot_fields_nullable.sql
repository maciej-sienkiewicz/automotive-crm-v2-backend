-- Migration: Make visit snapshot fields license_plate_snapshot and year_of_production_snapshot nullable
-- Description: Allows visits to store null values for license plate and production year snapshots when vehicle doesn't have these fields
-- Date: 2026-01-19

-- Step 1: Make license_plate_snapshot nullable
ALTER TABLE visits
    ALTER COLUMN license_plate_snapshot DROP NOT NULL;

-- Step 2: Make year_of_production_snapshot nullable
ALTER TABLE visits
    ALTER COLUMN year_of_production_snapshot DROP NOT NULL;

-- Comments for documentation
COMMENT ON COLUMN visits.license_plate_snapshot IS 'Immutable snapshot of vehicle license plate at visit creation - optional';
COMMENT ON COLUMN visits.year_of_production_snapshot IS 'Immutable snapshot of vehicle production year at visit creation - optional';
