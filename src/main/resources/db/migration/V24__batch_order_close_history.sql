-- Migration: Create batch_order_close_history table
-- Description: Stores a record for every month-close operation on a batch contractor

CREATE TABLE batch_order_close_history (
    id UUID PRIMARY KEY,
    studio_id UUID NOT NULL,
    contractor_id UUID NOT NULL,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    mode VARCHAR(20) NOT NULL,
    entry_count INT NOT NULL,
    total_net_cents BIGINT NOT NULL,
    total_gross_cents BIGINT NOT NULL,
    email_sent BOOLEAN NOT NULL DEFAULT FALSE,
    email_to VARCHAR(255),
    closed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_close_history_contractor
        FOREIGN KEY (contractor_id)
        REFERENCES batch_contractors(id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_batch_close_history_studio_contractor
    ON batch_order_close_history(studio_id, contractor_id);

ALTER TABLE batch_order_entries
    ADD CONSTRAINT fk_batch_entry_close_history
        FOREIGN KEY (close_history_id)
        REFERENCES batch_order_close_history(id)
        ON DELETE SET NULL;
