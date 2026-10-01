-- ═══════════════════════════════════════════════════════════════════════════════
-- Listy miesięczne: jeden przepływ na miesiąc (docs/api-worktime-months.md).
--
-- Karty zbierane → karty zatwierdzane → lista obecności podpisana. Dotąd lista mogła
-- powstać z niezłożonych kart i nie wiedziała, że karta zmieniła się po podpisie.
-- ═══════════════════════════════════════════════════════════════════════════════

-- Ostatnie przypomnienie o karcie za ten miesiąc: ta sama osoba dostaje push najwyżej
-- raz na 12 godzin, niezależnie od tego, ilu menedżerów kliknie „Przypomnij".
ALTER TABLE work_time_periods ADD COLUMN IF NOT EXISTS reminded_at TIMESTAMPTZ;

-- Lista nieaktualna: po jej wygenerowaniu karta z listy została odblokowana albo
-- zatwierdzono kartę, której na liście nie ma. Podpisana lista zostaje jako historia,
-- ale miesiąc wymaga nowego podpisu.
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS outdated_at TIMESTAMPTZ;

-- Osoby świadomie pominięte (karta niezatwierdzona w chwili generowania) - drukowane
-- w stopce PDF i zwracane w API, żeby lista nie udawała kompletnej.
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS excluded_names JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Konta, których karty są na liście. Lista z przepływu miesięcznego powstaje z kart
-- (kluczowanych kontem), a nie z rekordów pracowników - konto bez rekordu pracownika
-- też ma kartę. NULL dla list sprzed tej zmiany: tam skład opisuje employee_ids.
ALTER TABLE attendance_sheets ADD COLUMN IF NOT EXISTS user_ids JSONB;

CREATE INDEX IF NOT EXISTS idx_attendance_sheets_studio_period
    ON attendance_sheets (studio_id, period, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_work_time_periods_studio_status
    ON work_time_periods (studio_id, status);
