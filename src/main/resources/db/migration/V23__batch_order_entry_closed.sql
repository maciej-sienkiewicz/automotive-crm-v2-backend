-- Migration: Add is_closed flag and close_history_id to batch_order_entries
-- Description: Enables month-close workflow – marking entries as closed and linking to a history record

ALTER TABLE batch_order_entries
    ADD COLUMN is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN close_history_id UUID;

CREATE INDEX IF NOT EXISTS idx_batch_order_entries_close_history
    ON batch_order_entries(close_history_id)
    WHERE close_history_id IS NOT NULL;
