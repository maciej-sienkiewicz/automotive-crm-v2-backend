-- Add damage_map_file_id column to visits table
-- This column stores the S3 key for the generated damage map image

ALTER TABLE visits
ADD COLUMN damage_map_file_id VARCHAR(500) NULL;

COMMENT ON COLUMN visits.damage_map_file_id IS 'S3 key for the generated damage map image showing damage points on car schematic';
