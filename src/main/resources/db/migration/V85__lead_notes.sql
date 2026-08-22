-- Notatki na leadzie: „oddzwoniłem, prosił o kontakt po 15", „wyśle zdjęcia po
-- weekendzie". Ślad pracy z zapytaniem, którego nie niesie ani korespondencja
-- (telefon nie zostawia maila), ani historia statusów (status się nie zmienił).
CREATE TABLE IF NOT EXISTS lead_notes (
    id              UUID PRIMARY KEY,
    studio_id       UUID NOT NULL,
    lead_id         UUID NOT NULL,
    content         TEXT NOT NULL,
    created_by      UUID NOT NULL,
    created_by_name VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_lead_notes_lead_id ON lead_notes (lead_id);
CREATE INDEX IF NOT EXISTS idx_lead_notes_studio_id ON lead_notes (studio_id);
