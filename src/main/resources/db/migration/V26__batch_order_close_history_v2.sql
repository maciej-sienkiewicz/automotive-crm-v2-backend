-- Add new columns for the updated schema
ALTER TABLE batch_order_close_history
    ADD COLUMN IF NOT EXISTS from_date DATE,
    ADD COLUMN IF NOT EXISTS to_date DATE,
    ADD COLUMN IF NOT EXISTS email_to VARCHAR(255);

-- Migrate data from old columns
UPDATE batch_order_close_history
SET from_date = COALESCE(period_from, '1970-01-01'),
    to_date   = COALESCE(period_to, '1970-01-01'),
    email_to  = email_recipient;

-- Enforce NOT NULL on migrated columns
ALTER TABLE batch_order_close_history
    ALTER COLUMN from_date SET NOT NULL,
    ALTER COLUMN to_date SET NOT NULL;

-- Remove old columns
ALTER TABLE batch_order_close_history
    DROP COLUMN IF EXISTS period_from,
    DROP COLUMN IF EXISTS period_to,
    DROP COLUMN IF EXISTS closed_by_user_id,
    DROP COLUMN IF EXISTS closed_by_user_name,
    DROP COLUMN IF EXISTS closed_entry_ids,
    DROP COLUMN IF EXISTS finance_entry_created,
    DROP COLUMN IF EXISTS email_requested,
    DROP COLUMN IF EXISTS email_recipient;

-- Add FK from contractor_id to batch_contractors
ALTER TABLE batch_order_close_history
    ADD CONSTRAINT fk_close_history_contractor
        FOREIGN KEY (contractor_id)
        REFERENCES batch_contractors (id)
        ON DELETE CASCADE;

-- Replace old index with studio-first index the entity expects
DROP INDEX IF EXISTS idx_batch_close_history_contractor;
CREATE INDEX IF NOT EXISTS idx_batch_close_history_studio_contractor
    ON batch_order_close_history (studio_id, contractor_id);

-- Add close_history_id to batch_order_entries
ALTER TABLE batch_order_entries
    ADD COLUMN IF NOT EXISTS close_history_id UUID;

-- FK from entries to close history
ALTER TABLE batch_order_entries
    ADD CONSTRAINT fk_batch_entry_close_history
        FOREIGN KEY (close_history_id)
        REFERENCES batch_order_close_history (id)
        ON DELETE SET NULL;

-- Partial index for efficient snapshot lookups
CREATE INDEX IF NOT EXISTS idx_batch_order_entries_close_history
    ON batch_order_entries (close_history_id)
    WHERE close_history_id IS NOT NULL;
