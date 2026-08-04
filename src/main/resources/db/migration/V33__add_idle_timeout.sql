ALTER TABLE studio_settings
    ADD COLUMN idle_timeout_minutes INT NOT NULL DEFAULT 0;
