-- ═══════════════════════════════════════════════════════════════════════════════
-- Service catalog for the batch-orders module ("Zlecenia zbiorcze").
--
-- Until now every service performed for a B2B contractor was retyped by hand into
-- the entry form, prices included. The same "Mycie zewnętrzne" was therefore entered
-- dozens of times, each time at whatever price the operator remembered, and a price
-- change had to be recalled rather than looked up. This table gives the module its
-- own catalog: a name entered once becomes a suggestion with a price attached.
--
-- Deliberately separate from the main `services` table. These are B2B settlement
-- positions with their own names and their own (usually contract) prices; mixing
-- them into the retail catalog would pollute the service picker used at check-in
-- and make the retail price list answer to two different pricing regimes.
--
-- Entries do NOT reference this table. `batch_order_entry_services` keeps its own
-- snapshot of name and amounts, exactly as before, so editing or deleting a catalog
-- position can never move a number on an already-recorded — let alone an already
-- settled — entry. The catalog is a source of suggestions, never a source of truth
-- for what was invoiced.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS batch_order_services (
    id                 UUID PRIMARY KEY,
    studio_id          UUID NOT NULL,
    name               VARCHAR(500) NOT NULL,
    net_amount_cents   BIGINT NOT NULL DEFAULT 0,
    gross_amount_cents BIGINT NOT NULL DEFAULT 0,
    vat_rate           INTEGER NOT NULL DEFAULT 23,
    -- Soft delete: a removed position disappears from suggestions but its row stays,
    -- so nothing that once pointed at it (reports, exports, this history) breaks.
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_batch_order_services_studio
    ON batch_order_services(studio_id, is_active);

-- One active position per name per studio. Case-insensitive, because "Mycie" and
-- "mycie" are the same service to everyone except a byte comparison; scoped to
-- active rows so a name can be reused after its position was removed.
CREATE UNIQUE INDEX IF NOT EXISTS uq_batch_order_services_studio_name
    ON batch_order_services(studio_id, LOWER(name))
    WHERE is_active;

-- Seed from what studios already typed, so the catalog is useful on day one instead
-- of after everyone has re-entered their list. Per name the most recent entry wins:
-- of two spellings of the same price history, the latest is the one still in use.
INSERT INTO batch_order_services (
    id, studio_id, name, net_amount_cents, gross_amount_cents, vat_rate, is_active, created_at, updated_at
)
SELECT gen_random_uuid(), src.studio_id, src.name,
       src.net_amount_cents, src.gross_amount_cents, src.vat_rate,
       TRUE, NOW(), NOW()
FROM (
    SELECT DISTINCT ON (e.studio_id, LOWER(BTRIM(s.service_name)))
           e.studio_id,
           BTRIM(s.service_name)  AS name,
           s.net_amount_cents,
           s.gross_amount_cents,
           s.vat_rate
    FROM batch_order_entry_services s
    JOIN batch_order_entries e ON e.id = s.entry_id
    WHERE BTRIM(s.service_name) <> ''
    ORDER BY e.studio_id, LOWER(BTRIM(s.service_name)), e.service_date DESC, e.created_at DESC
) src
ON CONFLICT DO NOTHING;

COMMENT ON TABLE batch_order_services IS
    'Suggestion catalog for batch-order entry services. Entries snapshot their own copy, so edits here never touch recorded data.';
COMMENT ON COLUMN batch_order_services.is_active IS
    'FALSE = removed from suggestions; the row is kept so historical references stay resolvable.';
