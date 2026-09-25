-- ═══════════════════════════════════════════════════════════════════════════════
-- Raport właściciela (tygodniowy / dwutygodniowy PDF).
--
-- visits.started_at: chwila rozpoczęcia prac, czyli przejście DRAFT → IN_PROGRESS.
-- Dotąd nic jej nie zapisywało — created_at to otwarcie formularza przyjęcia,
-- które potrafi wyprzedzić podpisanie dokumentów o godziny, a draft porzucony
-- nigdy nie był wizytą. Raport liczy „wizyty rozpoczęte" właśnie po tej kolumnie.
--
-- Uzupełnienie wstecz: wpis VISIT_CREATED („Rozpoczęto wizytę") w dzienniku
-- zdarzeń powstaje na końcu przyjęcia, sekundy przed potwierdzeniem — to
-- najlepsze przybliżenie, jakie zostało. Wizyty bez takiego wpisu dostają
-- created_at. Drafty zostają z NULL: ich praca się jeszcze nie zaczęła.
--
-- owner_report_settings: czy i jak często studio dostaje raport mailem.
-- Brak wiersza = OFF — nikt nie dostaje maila, o który nie prosił.
--
-- owner_report_dispatches: jeden wiersz na wysłany okres. Unikalność
-- (studio, okres) jest blokadą między instancjami: raport idzie tylko z tej,
-- której wstawienie się udało.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE visits
    ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE;

UPDATE visits v
SET started_at = COALESCE(
        (SELECT MIN(a.created_at)
         FROM audit_logs a
         WHERE a.studio_id = v.studio_id
           AND a.visit_id = v.id
           AND a.action = 'VISIT_CREATED'),
        v.created_at
    )
WHERE v.status <> 'DRAFT'
  AND v.started_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_visits_studio_started
    ON visits (studio_id, started_at);

CREATE INDEX IF NOT EXISTS idx_visits_studio_pickup
    ON visits (studio_id, pickup_date);

CREATE TABLE IF NOT EXISTS owner_report_settings (
    studio_id  UUID PRIMARY KEY,
    frequency  VARCHAR(20) NOT NULL DEFAULT 'OFF',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS owner_report_dispatches (
    id           UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    recipients   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_owner_report_dispatch UNIQUE (studio_id, period_start, period_end)
);
