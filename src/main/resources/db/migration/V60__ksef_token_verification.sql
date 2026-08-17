-- Persist the result of the last KSeF token verification so the settings view
-- can show permission status without re-authenticating against KSeF on every load.
ALTER TABLE ksef_credentials ADD COLUMN last_verified_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ksef_credentials ADD COLUMN verified_token_valid BOOLEAN;
ALTER TABLE ksef_credentials ADD COLUMN verified_permissions VARCHAR(500);
