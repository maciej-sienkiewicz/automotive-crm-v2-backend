-- Przebudowa modułu Instagram v2:
-- 1. Wycofanie stories (decyzja prawna – nie przechowujemy treści ulotnych).
-- 2. Usunięcie image_url (nie przechowujemy mediów; prezentacja przez permalink/oEmbed).
-- 3. Flaga is_self ("Twoje studio" w benchmarku).
-- 4. Nowe tabele: agregaty tygodniowe, tematy postów, insighty.

DROP TABLE IF EXISTS instagram_story_snapshots;

ALTER TABLE instagram_post_snapshots DROP COLUMN IF EXISTS image_url;

ALTER TABLE studio_instagram_profiles
    ADD COLUMN IF NOT EXISTS is_self BOOLEAN NOT NULL DEFAULT FALSE;

-- Tygodniowe agregaty per profil – liczone przy syncu, czytane przez API analityczne
CREATE TABLE IF NOT EXISTS instagram_profile_stats_weekly (
    id              UUID PRIMARY KEY,
    profile_id      UUID NOT NULL,
    week_start      DATE NOT NULL,
    post_count      INTEGER NOT NULL DEFAULT 0,
    reels_count     INTEGER NOT NULL DEFAULT 0,
    carousel_count  INTEGER NOT NULL DEFAULT 0,
    photo_count     INTEGER NOT NULL DEFAULT 0,
    total_likes     INTEGER NOT NULL DEFAULT 0,
    total_comments  INTEGER NOT NULL DEFAULT 0,
    total_views     BIGINT,
    er_pct          DOUBLE PRECISION,
    follower_end    INTEGER,
    follower_delta  INTEGER,
    computed_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_stats_profile_week ON instagram_profile_stats_weekly(profile_id, week_start);
CREATE INDEX IF NOT EXISTS idx_ig_stats_week ON instagram_profile_stats_weekly(week_start);

-- Etykiety tematyczne postów (słownik branżowy detailingu)
CREATE TABLE IF NOT EXISTS instagram_post_topics (
    id             UUID PRIMARY KEY,
    post_id        UUID NOT NULL,
    topic          VARCHAR(40) NOT NULL,
    is_promo       BOOLEAN NOT NULL DEFAULT FALSE,
    is_contest     BOOLEAN NOT NULL DEFAULT FALSE,
    discount_pct   INTEGER,
    method         VARCHAR(10) NOT NULL,
    classified_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_topics_post ON instagram_post_topics(post_id);
CREATE INDEX IF NOT EXISTS idx_ig_topics_topic ON instagram_post_topics(topic);
CREATE INDEX IF NOT EXISTS idx_ig_topics_promo ON instagram_post_topics(is_promo);

-- Insighty – gotowe wnioski per studio
CREATE TABLE IF NOT EXISTS instagram_insights (
    id              UUID PRIMARY KEY,
    studio_id       UUID NOT NULL,
    type            VARCHAR(40) NOT NULL,
    severity        VARCHAR(10) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT NOT NULL,
    action_text     TEXT NOT NULL,
    profile_id      UUID,
    post_id         UUID,
    probable_cause  VARCHAR(60),
    dedup_key       VARCHAR(120) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    week_start      DATE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ig_insights_studio_created ON instagram_insights(studio_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_insights_studio_dedup ON instagram_insights(studio_id, dedup_key);
CREATE INDEX IF NOT EXISTS idx_ig_insights_studio_status ON instagram_insights(studio_id, status);
