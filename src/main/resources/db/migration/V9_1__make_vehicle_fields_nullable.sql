-- Migration: Make vehicle license_plate and year_of_production nullable
-- Description: Allows vehicles to be created without license plate or production year
-- Date: 2026-01-19

-- Step 1: Make license_plate nullable
ALTER TABLE vehicles
    ALTER COLUMN license_plate DROP NOT NULL;

-- Step 2: Make year_of_production nullable
ALTER TABLE vehicles
    ALTER COLUMN year_of_production DROP NOT NULL;

-- Comments for documentation
COMMENT ON COLUMN vehicles.license_plate IS 'Vehicle license plate (registration number) - optional';
COMMENT ON COLUMN vehicles.year_of_production IS 'Year the vehicle was produced - optional';
