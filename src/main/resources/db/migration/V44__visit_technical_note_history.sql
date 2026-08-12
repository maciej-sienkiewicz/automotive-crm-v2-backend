-- History table for visit technical notes.
-- Each write to visit.technical_notes triggers an insert here,
-- giving us a full, queryable timeline of every change.

CREATE TABLE visit_technical_note_history (
    id              UUID                        PRIMARY KEY DEFAULT gen_random_uuid(),
    visit_id        UUID                        NOT NULL REFERENCES visits(id) ON DELETE CASCADE,
    studio_id       UUID                        NOT NULL,
    content         TEXT,
    action          VARCHAR(10)                 NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'CLEARED')),
    changed_by_id   UUID                        NOT NULL,
    changed_by_name VARCHAR(255)                NOT NULL,
    changed_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vtnh_visit_id  ON visit_technical_note_history(visit_id);
CREATE INDEX idx_vtnh_studio_id ON visit_technical_note_history(studio_id);
CREATE INDEX idx_vtnh_changed_at ON visit_technical_note_history(visit_id, changed_at DESC);
