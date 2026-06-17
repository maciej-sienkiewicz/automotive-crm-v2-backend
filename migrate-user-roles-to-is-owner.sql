-- Migrate users.role (enum: OWNER/MANAGER/DETAILER) to users.is_owner (boolean)
-- Run this BEFORE deploying the new application version.

ALTER TABLE users ADD COLUMN IF NOT EXISTS is_owner BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE users SET is_owner = TRUE WHERE role = 'OWNER';

-- The old 'role' column is no longer used by the application.
-- Hibernate (ddl-auto=update) will not drop it automatically.
-- Drop it manually once migration is verified:
-- ALTER TABLE users DROP COLUMN role;
