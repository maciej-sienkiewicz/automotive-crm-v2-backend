-- Manual fix for appointments_status_check constraint
-- Use this ONLY if Flyway migration fails
-- Connect to database and run: psql -U postgres -d detailing_crm -f manual-fix-constraint.sql

-- Drop old constraint
ALTER TABLE appointments
DROP CONSTRAINT IF EXISTS appointments_status_check;

-- Add new constraint with CONVERTED status
ALTER TABLE appointments
ADD CONSTRAINT appointments_status_check
CHECK (status IN ('CREATED', 'CONFIRMED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'CONVERTED'));

-- Verify the constraint
SELECT
    conname AS constraint_name,
    pg_get_constraintdef(oid) AS constraint_definition
FROM pg_constraint
WHERE conname = 'appointments_status_check';

-- Show current Flyway migration status
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
