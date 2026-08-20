-- ═══════════════════════════════════════════════════════════════════════════════
-- 1. Tag catalogue per studio — tags become editable instead of compiled in.
--
-- The closed LeadTag enum answered "what do people ask about" only as far as the
-- list we guessed up front. A studio that sells something we never thought of had
-- no way to count it. The catalogue is per studio and seeded from the old enum, so
-- nothing that already exists changes meaning.
--
-- Deleting a tag ARCHIVES it (archived_at) rather than dropping the row: leads keep
-- their tag_code, so past analytics stay readable and the label still resolves. Only
-- the picker hides archived entries.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS lead_tag_definitions (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    -- Stable machine code stored on lead_tags. Never changes once handed out.
    code        VARCHAR(50) NOT NULL,
    label       VARCHAR(80) NOT NULL,
    sort_order  INTEGER NOT NULL DEFAULT 0,
    archived_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_tag_definitions_studio_code
    ON lead_tag_definitions(studio_id, code);

CREATE INDEX IF NOT EXISTS idx_lead_tag_definitions_studio
    ON lead_tag_definitions(studio_id, sort_order);

-- Seed every existing studio with the seven codes the enum used to hold, so the
-- picker looks the same the day after the deploy as it did the day before.
INSERT INTO lead_tag_definitions (id, studio_id, code, label, sort_order, created_at)
SELECT gen_random_uuid(), s.id, d.code, d.label, d.sort_order, NOW()
FROM studios s
CROSS JOIN (VALUES
    ('CERAMIC_COATING',    'Powłoka ceramiczna',    0),
    ('PPF_WRAP',           'Folia PPF / oklejanie', 1),
    ('CORRECTION_POLISH',  'Korekta lakieru',       2),
    ('INTERIOR',           'Detailing wnętrza',     3),
    ('WASH_MAINTENANCE',   'Mycie i pielęgnacja',   4),
    ('FULL_DETAILING',     'Pełny detailing',       5),
    ('OTHER',              'Inne',                  6)
) AS d(code, label, sort_order)
ON CONFLICT DO NOTHING;

-- ═══════════════════════════════════════════════════════════════════════════════
-- 2. Notes on a contact, plus their full trail.
--
-- Keyed by e-mail address, not by customer id: the address exists from the first
-- message, the customer record often only later. A note written about someone who
-- is not in the kartoteka yet must not be lost the moment they are added to it.
--
-- Deletion is soft. "Who removed that note and when" is exactly the question this
-- feature is meant to answer, and a deleted row answers nothing.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS contact_notes (
    id              UUID PRIMARY KEY,
    studio_id       UUID NOT NULL,
    -- Always lower-cased at the application boundary; comparisons rely on it.
    contact_email   VARCHAR(320) NOT NULL,
    body            TEXT NOT NULL,
    created_by_id   UUID,
    created_by_name VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_contact_notes_studio_email
    ON contact_notes(studio_id, contact_email, created_at DESC);

CREATE TABLE IF NOT EXISTS contact_note_events (
    id            UUID PRIMARY KEY,
    studio_id     UUID NOT NULL,
    contact_email VARCHAR(320) NOT NULL,
    note_id       UUID NOT NULL,
    -- CREATED / UPDATED / DELETED
    action        VARCHAR(20) NOT NULL,
    body_before   TEXT,
    body_after    TEXT,
    actor_id      UUID,
    actor_name    VARCHAR(200) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_contact_note_events_studio_email
    ON contact_note_events(studio_id, contact_email, created_at DESC);
