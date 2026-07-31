CREATE TABLE batch_order_close_history
(
    id                    UUID PRIMARY KEY         DEFAULT gen_random_uuid(),
    studio_id             UUID                     NOT NULL,
    contractor_id         UUID                     NOT NULL,
    closed_by_user_id     UUID                     NOT NULL,
    closed_by_user_name   VARCHAR(255),
    closed_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    period_from           DATE,
    period_to             DATE,
    entry_count           INT                      NOT NULL,
    total_net_cents       BIGINT                   NOT NULL,
    total_gross_cents     BIGINT                   NOT NULL,
    mode                  VARCHAR(20)              NOT NULL DEFAULT 'ALL',
    finance_entry_created BOOLEAN                  NOT NULL DEFAULT FALSE,
    email_requested       BOOLEAN                  NOT NULL DEFAULT FALSE,
    email_sent            BOOLEAN                  NOT NULL DEFAULT FALSE,
    email_recipient       VARCHAR(255),
    closed_entry_ids      TEXT                     NOT NULL DEFAULT '[]',
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_batch_close_history_contractor
    ON batch_order_close_history (contractor_id, studio_id, closed_at DESC);
