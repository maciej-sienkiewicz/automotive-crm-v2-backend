-- Wydarzenia w kalendarzu: własne wpisy studia, które nie są ani wizytą, ani
-- rezerwacją — urlop, szkolenie, dostawa chemii, remont hali. Wiszą na dniach
-- (od-do), bo tak są planowane; godziny nie niosą tu żadnej informacji.
CREATE TABLE IF NOT EXISTS calendar_events (
    id              UUID PRIMARY KEY,
    studio_id       UUID NOT NULL,
    title           VARCHAR(200) NOT NULL,
    description     TEXT,
    start_date      DATE NOT NULL,
    -- Domknięty koniec zakresu: dzień, w którym wydarzenie jeszcze trwa.
    end_date        DATE NOT NULL,
    created_by      UUID NOT NULL,
    created_by_name VARCHAR(200) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_calendar_events_range CHECK (end_date >= start_date)
);

-- Kalendarz pyta zawsze o zakres dat w obrębie jednego studia.
CREATE INDEX IF NOT EXISTS idx_calendar_events_studio_range
    ON calendar_events (studio_id, start_date, end_date);
