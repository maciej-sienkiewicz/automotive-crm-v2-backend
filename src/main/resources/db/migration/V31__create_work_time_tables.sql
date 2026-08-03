CREATE TABLE IF NOT EXISTS work_time_entries (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL,
    studio_id           UUID NOT NULL,
    date                DATE NOT NULL,
    minutes             INT  NOT NULL CHECK (minutes >= 0 AND minutes <= 1440),
    note                VARCHAR(500),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_work_time_entry_user_date UNIQUE (user_id, date)
);

CREATE INDEX IF NOT EXISTS idx_work_time_entries_user_id   ON work_time_entries(user_id);
CREATE INDEX IF NOT EXISTS idx_work_time_entries_studio    ON work_time_entries(studio_id);
CREATE INDEX IF NOT EXISTS idx_work_time_entries_user_date ON work_time_entries(user_id, date);

CREATE TABLE IF NOT EXISTS work_time_periods (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL,
    studio_id   UUID         NOT NULL,
    period      VARCHAR(7)   NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    submitted_at   TIMESTAMP WITH TIME ZONE,
    approved_at    TIMESTAMP WITH TIME ZONE,
    approved_by    UUID,
    returned_at    TIMESTAMP WITH TIME ZONE,
    returned_by    UUID,
    return_note    VARCHAR(1000),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_work_time_period_user_period UNIQUE (user_id, period)
);

CREATE INDEX IF NOT EXISTS idx_work_time_periods_user_id  ON work_time_periods(user_id);
CREATE INDEX IF NOT EXISTS idx_work_time_periods_studio   ON work_time_periods(studio_id);
CREATE INDEX IF NOT EXISTS idx_work_time_periods_user_per ON work_time_periods(user_id, period);
