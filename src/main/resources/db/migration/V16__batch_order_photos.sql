CREATE TABLE IF NOT EXISTS batch_order_entry_photos (
    id                UUID         NOT NULL PRIMARY KEY,
    studio_id         UUID         NOT NULL,
    entry_id          UUID         NOT NULL REFERENCES batch_order_entries(id) ON DELETE CASCADE,
    contractor_id     UUID         NOT NULL,
    file_id           VARCHAR(500) NOT NULL,
    file_name         VARCHAR(500) NOT NULL,
    description       TEXT,
    uploaded_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    uploaded_by       UUID,
    uploaded_by_name  VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_batch_order_photos_entry  ON batch_order_entry_photos (entry_id);
CREATE INDEX IF NOT EXISTS idx_batch_order_photos_studio ON batch_order_entry_photos (studio_id);
