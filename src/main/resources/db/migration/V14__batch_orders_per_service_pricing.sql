-- Migration: Per-service pricing for batch order entries
-- Each service item now carries its own net/gross/vat instead of one price per entry.

ALTER TABLE batch_order_entry_services
    ADD COLUMN net_amount_cents  BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN gross_amount_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN vat_rate          INTEGER NOT NULL DEFAULT 23;

ALTER TABLE batch_order_entries
    DROP COLUMN IF EXISTS net_amount_cents,
    DROP COLUMN IF EXISTS gross_amount_cents,
    DROP COLUMN IF EXISTS vat_rate;

COMMENT ON COLUMN batch_order_entry_services.net_amount_cents   IS 'Net price in cents (grosze) for this individual service';
COMMENT ON COLUMN batch_order_entry_services.gross_amount_cents IS 'Gross price in cents (grosze) for this individual service';
COMMENT ON COLUMN batch_order_entry_services.vat_rate           IS 'VAT rate for this service (23, 8, 5, 0, or -1 for ZW)';
