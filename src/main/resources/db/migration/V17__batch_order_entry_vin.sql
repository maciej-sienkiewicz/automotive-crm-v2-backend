ALTER TABLE batch_order_entries
    ADD COLUMN IF NOT EXISTS vehicle_vin VARCHAR(17);

CREATE INDEX IF NOT EXISTS idx_batch_order_entries_vin
    ON batch_order_entries (studio_id, vehicle_vin)
    WHERE vehicle_vin IS NOT NULL;
