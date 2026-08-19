-- ═══════════════════════════════════════════════════════════════════════════════
-- Per-user mail signature for the webmail module.
--
-- A signature belongs to the PERSON who writes, not to the studio: two people
-- replying from the same shared mailbox (biuro@…) sign with their own name, phone
-- and role. Tying it to the studio would force one shared footer and make every
-- reply look like it came from the same anonymous "biuro".
--
-- Kept in the comms module rather than as columns on `users`, so the mail feature
-- owns its own storage and the users table does not accumulate per-feature fields.
--
-- body_html is stored as authored and sanitised on the way out (same sanitiser as
-- foreign mail), because what is safe to store and what is safe to send are decided
-- by different code paths and the sending path is the one that must be certain.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS comm_user_signatures (
    user_id            UUID PRIMARY KEY,
    studio_id          UUID NOT NULL,
    body_html          TEXT NOT NULL,
    -- Whether the composer starts with the signature switched on. The user still
    -- decides per message; this is only what the toggle defaults to.
    enabled_by_default BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_comm_user_signatures_studio
    ON comm_user_signatures(studio_id);
