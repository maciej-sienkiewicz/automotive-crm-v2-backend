-- Baseline schematu modułu Instagram / monitoring konkurencji.
-- Tabele istniały dotąd wyłącznie przez hibernate ddl-auto=update (dev);
-- CREATE TABLE IF NOT EXISTS pozwala tej migracji przejść zarówno na czystej
-- bazie produkcyjnej (flyway + ddl-auto=validate), jak i na bazie dev,
-- gdzie tabele już są.

CREATE TABLE IF NOT EXISTS instagram_profiles (
    id                      UUID PRIMARY KEY,
    username                VARCHAR(30) NOT NULL,
    instagram_user_id       VARCHAR(30),
    api_error               BOOLEAN NOT NULL DEFAULT FALSE,
    follower_count          INTEGER,
    following_count         INTEGER,
    media_count             INTEGER,
    biography               TEXT,
    external_url            TEXT,
    has_contact_data        BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified             BOOLEAN NOT NULL DEFAULT FALSE,
    is_business             BOOLEAN NOT NULL DEFAULT FALSE,
    account_type            INTEGER,
    category                VARCHAR(100),
    has_highlight_reels     BOOLEAN NOT NULL DEFAULT FALSE,
    total_clips_count       INTEGER NOT NULL DEFAULT 0,
    is_private              BOOLEAN NOT NULL DEFAULT FALSE,
    details_last_synced_at  TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_profiles_username ON instagram_profiles(username);
CREATE INDEX IF NOT EXISTS idx_ig_profiles_api_error ON instagram_profiles(api_error);

CREATE TABLE IF NOT EXISTS studio_instagram_profiles (
    id                UUID PRIMARY KEY,
    studio_id         UUID NOT NULL,
    profile_id        UUID NOT NULL,
    status            VARCHAR(20) NOT NULL,
    added_by_user_id  UUID NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_studio_ig_studio_status ON studio_instagram_profiles(studio_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_studio_ig_studio_profile ON studio_instagram_profiles(studio_id, profile_id);
CREATE INDEX IF NOT EXISTS idx_studio_ig_profile ON studio_instagram_profiles(profile_id);

CREATE TABLE IF NOT EXISTS instagram_post_snapshots (
    id                    UUID PRIMARY KEY,
    profile_id            UUID NOT NULL,
    post_pk               VARCHAR(30) NOT NULL,
    post_code             VARCHAR(30) NOT NULL,
    like_count            INTEGER NOT NULL DEFAULT 0,
    comment_count         INTEGER NOT NULL DEFAULT 0,
    view_count            BIGINT,
    caption               TEXT,
    taken_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    scraped_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    product_type          VARCHAR(50),
    carousel_media_count  INTEGER,
    hashtags              TEXT,
    image_url             TEXT
);

CREATE INDEX IF NOT EXISTS idx_ig_posts_profile_scraped ON instagram_post_snapshots(profile_id, scraped_at);
CREATE INDEX IF NOT EXISTS idx_ig_posts_profile_taken ON instagram_post_snapshots(profile_id, taken_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_posts_post_pk ON instagram_post_snapshots(post_pk);

CREATE TABLE IF NOT EXISTS instagram_profile_metrics_snapshots (
    id               UUID PRIMARY KEY,
    profile_id       UUID NOT NULL,
    snapshot_date    DATE NOT NULL,
    follower_count   INTEGER,
    following_count  INTEGER,
    media_count      INTEGER,
    snapshot_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_metrics_profile_date ON instagram_profile_metrics_snapshots(profile_id, snapshot_date);
CREATE INDEX IF NOT EXISTS idx_ig_metrics_profile_id ON instagram_profile_metrics_snapshots(profile_id);

CREATE TABLE IF NOT EXISTS studio_instagram_post_reactions (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    post_id     UUID NOT NULL,
    reaction    VARCHAR(20) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ig_reactions_studio_post ON studio_instagram_post_reactions(studio_id, post_id);
CREATE INDEX IF NOT EXISTS idx_ig_reactions_studio ON studio_instagram_post_reactions(studio_id);
CREATE INDEX IF NOT EXISTS idx_ig_reactions_post ON studio_instagram_post_reactions(post_id);
