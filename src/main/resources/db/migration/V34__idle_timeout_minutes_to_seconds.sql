-- Rename idle_timeout_minutes → idle_timeout_seconds and convert existing values (minutes → seconds).
ALTER TABLE studio_settings
    ADD COLUMN idle_timeout_seconds INT NOT NULL DEFAULT 0;

UPDATE studio_settings
    SET idle_timeout_seconds = idle_timeout_minutes * 60
    WHERE idle_timeout_minutes > 0;

ALTER TABLE studio_settings
    DROP COLUMN idle_timeout_minutes;
