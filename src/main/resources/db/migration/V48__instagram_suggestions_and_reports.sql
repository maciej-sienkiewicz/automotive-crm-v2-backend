-- Sugestie podobnych profili (cache related-profiles) + raporty tygodniowe.

CREATE TABLE IF NOT EXISTS instagram_profile_suggestions (
    id                  UUID PRIMARY KEY,
    profile_id          UUID NOT NULL,
    suggested_username  VARCHAR(30) NOT NULL,
    full_name           VARCHAR(150),
    is_verified         BOOLEAN NOT NULL DEFAULT FALSE,
    fetched_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ig_suggestions_profile ON instagram_profile_suggestions(profile_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_suggestions_profile_username
    ON instagram_profile_suggestions(profile_id, suggested_username);

CREATE TABLE IF NOT EXISTS instagram_reports (
    id                UUID PRIMARY KEY,
    studio_id         UUID NOT NULL,
    period_start      DATE NOT NULL,
    period_end        DATE NOT NULL,
    title             VARCHAR(120) NOT NULL,
    lead              TEXT NOT NULL,
    narrative_source  VARCHAR(10) NOT NULL,
    payload           TEXT NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_reports_studio_period ON instagram_reports(studio_id, period_start);
CREATE INDEX IF NOT EXISTS idx_ig_reports_studio ON instagram_reports(studio_id);
